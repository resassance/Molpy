package app.musicplayer.playback

import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Service-owned, dispatcher-confined orchestration. Resolve completion is never a playing event. */
class PlaybackCoordinator(
    private val engine: PlayerEngine,
    private val resolver: PlaybackResolver,
    private val network: () -> NetworkContext,
    scope: CoroutineScope,
) {
    private val state = MutableStateFlow(PlaybackSnapshot())
    val snapshot: StateFlow<PlaybackSnapshot> = state.asStateFlow()
    private var generation = 0L
    private var resolution: Deferred<AppResult<ResolvedPlayback>>? = null
    private var resolving = false
    private val observation = scope.launch {
        engine.snapshot.collect { actual ->
            if (!resolving && actual.current != null && actual.current == state.value.current) state.value = actual
        }
    }

    suspend fun select(track: Track, entry: QueueEntry, positionMs: Long, playWhenReady: Boolean): AppResult<Unit> {
        require(entry.trackId == track.id) { "Queue entry and track identity differ" }
        val selectedGeneration = ++generation
        resolution?.cancel()
        resolving = true
        engine.stop()
        state.value = PlaybackSnapshot(PlayerStatus.RESOLVING, entry, track, positionMs.coerceAtLeast(0), null, playWhenReady)
        val candidate = track.candidates.firstOrNull()
            ?: return fail(AppError(ErrorCode.SOURCE_UNAVAILABLE, "playback.select", false))
        return try {
            coroutineScope {
                val pending = async { resolver.resolve(candidate, CAPABILITIES, network()) }
                resolution = pending
                val result = pending.await()
                ensureActive()
                if (selectedGeneration != generation) throw CancellationException("Superseded selection")
                when (result) {
                    is AppResult.Failure -> fail(result.error)
                    is AppResult.Success -> {
                        val intent = state.value
                        val loaded = engine.load(entry, PlayableSource.Remote(result.value, track), intent.positionMs, intent.playWhenReady)
                        if (selectedGeneration != generation) throw CancellationException("Superseded selection")
                        resolving = false
                        when (loaded) {
                            is AppResult.Failure -> fail(loaded.error)
                            is AppResult.Success -> { state.value = engine.snapshot.value; loaded }
                        }
                    }
                }
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
        val clamped = positionMs.coerceAtLeast(0).let { value -> state.value.durationMs?.let { value.coerceAtMost(it) } ?: value }
        if (resolving) state.value = state.value.copy(positionMs = clamped) else engine.seek(clamped)
    }
    fun stop() {
        ++generation
        resolution?.cancel()
        resolution = null
        resolving = false
        engine.stop()
        state.value = PlaybackSnapshot()
    }
    fun release() { stop(); observation.cancel(); engine.release() }

    private fun fail(error: AppError): AppResult.Failure {
        resolving = false
        state.value = state.value.copy(status = PlayerStatus.FAILED, error = error)
        return AppResult.Failure(error)
    }
    companion object {
        val CAPABILITIES = PlaybackCapabilities(setOf(TransportKind.PROGRESSIVE, TransportKind.HLS))
    }
}
