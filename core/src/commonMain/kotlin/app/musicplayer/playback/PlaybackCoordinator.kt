package app.musicplayer.playback

import app.musicplayer.diagnostics.DiagnosticEvent
import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Service-owned, dispatcher-confined orchestration. Resolve completion is never a playing event.
 */
class PlaybackCoordinator(
    private val engine: PlayerEngine,
    private val resolver: PlaybackResolver,
    private val network: () -> NetworkContext,
    private val scope: CoroutineScope,
    private val diagnostics: (DiagnosticEvent) -> Unit = {},
    private val providerVersion: String? = null,
    private val clock: () -> Long = { 0L },
    private val sourceSelector: PlaybackSourceSelector = PlaybackSourceSelector(resolver, network),
) {
    private val state = MutableStateFlow(PlaybackSnapshot())
    val snapshot: StateFlow<PlaybackSnapshot> = state.asStateFlow()
    private val attempt = MutableStateFlow(0L)
    val userAttempt: StateFlow<Long> = attempt.asStateFlow()
    private val recovery = RecoveryPolicy()
    private var generation = 0L
    private var resolveOperations = 0
    private var nextLoadId = 0L
    private var activeLoadId: Long? = null
    private var resolution: Deferred<AppResult<PlayableSource>>? = null
    private var activeLocal: AssetId? = null
    private var recoveryJob: Job? = null
    private var resolving = false
    private val observation = scope.launch {
        engine.snapshot.collect { actual ->
            if (
                !resolving &&
                    actual.current != null &&
                    actual.current == state.value.current &&
                    actual.loadId == activeLoadId
            ) {
                state.value = actual.copy(attemptId = attempt.value)
                actual.error
                    ?.takeIf { actual.status == PlayerStatus.FAILED }
                    ?.let { error ->
                        record("engine", error.code)
                        if (activeLocal == null) actual.candidateId?.let(resolver::invalidate)
                        if (activeLocal != null) {
                            val failedAsset = activeLocal!!
                            scope.launch { sourceSelector.localFailed(failedAsset) }
                        } else if (
                            resolveOperations < 2 && recovery.shouldResolveAgain(error, attemptId())
                        ) {
                            // Never await retry inside this sole engine observer: selections cancel
                            // retry work.
                            resolving = true
                            recoveryJob = scope.launch {
                                try {
                                    resolveRetained()
                                } catch (_: CancellationException) {
                                    currentCoroutineContext().ensureActive()
                                }
                            }
                        }
                    }
            }
        }
    }

    suspend fun select(
        track: Track,
        entry: QueueEntry,
        positionMs: Long,
        playWhenReady: Boolean,
    ): AppResult<Unit> {
        require(entry.trackId == track.id) { "Queue entry and track identity differ" }
        invalidate()
        attempt.value++
        recovery.resetForUserAttempt(attemptId())
        resolveOperations = 0
        state.value =
            PlaybackSnapshot(
                PlayerStatus.RESOLVING,
                entry,
                track,
                positionMs.coerceAtLeast(0),
                null,
                playWhenReady,
                attemptId = attempt.value,
            )
        return resolveRetained()
    }

    private suspend fun resolveRetained(): AppResult<Unit> {
        val selectedGeneration = ++generation
        resolving = true
        val retained = state.value
        val track = retained.track ?: return AppResult.Success(Unit)
        val entry = retained.current ?: return AppResult.Success(Unit)
        engine.stop()
        state.value =
            retained.copy(
                status = PlayerStatus.RESOLVING,
                error = null,
                bufferedPositionMs = null,
                loadId = null,
            )
        return try {
            coroutineScope {
                while (true) {
                    val started = clock()
                    var resolveStarted: Long? = null
                    val pending = async {
                        sourceSelector.select(track, { resolveOperations < 2 }) {
                            ensureActive()
                            resolveOperations++
                            resolveStarted = clock()
                            record("metadata", null, elapsed = clock() - started)
                        }
                    }
                    resolution = pending
                    val result = pending.await()
                    ensureActive()
                    if (selectedGeneration != generation)
                        throw CancellationException("Superseded selection")
                    if (resolveStarted == null)
                        record("metadata", result.errorOrNull()?.code, elapsed = clock() - started)
                    record(
                        "resolve",
                        result.errorOrNull()?.code,
                        elapsed = clock() - (resolveStarted ?: clock()),
                    )
                    val loaded =
                        when (result) {
                            is AppResult.Failure -> result
                            is AppResult.Success -> {
                                val intent = state.value
                                val position =
                                    intent.positionMs.coerceAtLeast(0).let {
                                        intent.durationMs?.let(it::coerceAtMost) ?: it
                                    }
                                val loadId = ++nextLoadId
                                activeLoadId = loadId
                                val source =
                                    when (val selected = result.value) {
                                        is PlayableSource.Local -> selected.copy(loadId = loadId)
                                        is PlayableSource.Remote -> selected.copy(loadId = loadId)
                                    }
                                activeLocal = (source as? PlayableSource.Local)?.assetId
                                state.value =
                                    state.value.copy(
                                        track = source.track,
                                        candidateId =
                                            when (source) {
                                                is PlayableSource.Local -> source.candidateId
                                                is PlayableSource.Remote -> source.lease.candidateId
                                            },
                                    )
                                val loadStarted = clock()
                                engine.load(entry, source, position, intent.playWhenReady).also {
                                    record(
                                        "load",
                                        it.errorOrNull()?.code,
                                        (source as? PlayableSource.Remote)?.lease?.transport?.name,
                                        elapsed = clock() - loadStarted,
                                    )
                                }
                            }
                        }
                    ensureActive()
                    if (selectedGeneration != generation)
                        throw CancellationException("Superseded selection")
                    when (loaded) {
                        is AppResult.Failure -> {
                            if (activeLocal == null)
                                state.value.candidateId?.let(resolver::invalidate)
                            activeLocal?.let { sourceSelector.localFailed(it) }
                            if (
                                activeLocal != null ||
                                    resolveOperations >= 2 ||
                                    !recovery.shouldResolveAgain(loaded.error, attemptId())
                            )
                                return@coroutineScope fail(loaded.error)
                        }
                        is AppResult.Success -> {
                            resolving = false
                            state.value =
                                engine.snapshot.value.let {
                                    it.copy(
                                        durationMs = it.durationMs ?: retained.durationMs,
                                        attemptId = attempt.value,
                                    )
                                }
                            return@coroutineScope loaded
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE") AppResult.Success(Unit)
            }
        } catch (cancelled: CancellationException) {
            if (selectedGeneration == generation) {
                resolving = false
                state.value = state.value.copy(status = PlayerStatus.PAUSED, playWhenReady = false)
            }
            throw cancelled
        } finally {
            if (selectedGeneration == generation) resolution = null
        }
    }

    fun play() {
        if (resolving) state.value = state.value.copy(playWhenReady = true) else engine.play()
    }

    fun pause() {
        if (resolving) state.value = state.value.copy(playWhenReady = false) else engine.pause()
    }

    fun seek(positionMs: Long) {
        val clamped =
            positionMs.coerceAtLeast(0).let { value ->
                state.value.durationMs?.let { value.coerceAtMost(it) } ?: value
            }
        if (resolving) state.value = state.value.copy(positionMs = clamped)
        else engine.seek(clamped)
    }

    private fun invalidate() {
        ++generation
        activeLoadId = null
        activeLocal = null
        resolution?.cancel()
        resolution = null
        recoveryJob?.cancel()
        recoveryJob = null
        resolving = false
    }

    fun stop() {
        invalidate()
        engine.stop()
        state.value = PlaybackSnapshot()
    }

    /**
     * Stop the old transport while retaining selection, commands and the attempt's recovery budget.
     */
    fun networkInvalidated() {
        if (activeLocal != null) return
        invalidate()
        val retained = state.value
        resolving = retained.current != null
        engine.stop()
        state.value =
            retained.copy(
                status = if (resolving) PlayerStatus.RESOLVING else PlayerStatus.IDLE,
                error = null,
            )
    }

    suspend fun networkActivated() {
        if (activeLocal != null) return
        if (!network().ready || state.value.current == null) return
        resolveRetained()
    }

    fun release() {
        stop()
        observation.cancel()
        engine.release()
    }

    private fun attemptId() = "playback-${attempt.value}"

    private fun record(
        stage: String,
        code: ErrorCode?,
        transport: String? = null,
        elapsed: Long = 0,
    ) {
        diagnostics(
            DiagnosticEvent(
                attemptId(),
                stage,
                providerVersion,
                state.value.candidateId?.value
                    ?: state.value.track?.candidates?.firstOrNull()?.id?.value,
                transport,
                code,
                elapsed.coerceAtLeast(0),
            )
        )
    }

    private fun fail(error: AppError): AppResult.Failure {
        resolving = false
        state.value = state.value.copy(status = PlayerStatus.FAILED, error = error)
        return AppResult.Failure(error)
    }

    companion object {
        val CAPABILITIES = PlaybackCapabilities(setOf(TransportKind.PROGRESSIVE, TransportKind.HLS))
    }
}
