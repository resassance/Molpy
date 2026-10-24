package app.musicplayer.queue

import app.musicplayer.catalog.*
import app.musicplayer.model.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class QueuePlaylistLoader(
    private val catalog: BrowseCatalog,
    private val queue: QueueController,
    private val commands: QueuePlaybackCommands,
    private val remember: suspend (List<Track>) -> AppResult<Unit>,
    private val commit: suspend (QueueSnapshot, List<Track>) -> AppResult<Unit>,
    ownerScope: CoroutineScope,
) {
    private val scope =
        CoroutineScope(
            ownerScope.coroutineContext + SupervisorJob(ownerScope.coroutineContext[Job])
        )
    private val seeds = mutableMapOf<String, PlaylistPlaybackRequest>()
    private var runtime: SourceRuntime? = null
    private var worker: Deferred<AppResult<Unit>>? = null
    private var requestGeneration = 0L
    private var sourceEpoch = 0L
    private var enabled = false
    private var ready = true
    private var closed = false

    private class SourceRuntime(
        val sessionId: String,
        var seedTail: List<PlaylistOccurrence>,
        var cursor: CatalogCursor?,
        var needsRecovery: Boolean = false,
    ) {
        var staged: StagedPage? = null
    }

    private data class StagedPage(
        val from: Int,
        val rows: List<PlaylistQueueRow>,
        val tracks: List<Track>,
        val next: CatalogCursor?,
        val complete: Boolean,
    )

    init {
        scope.launch {
            queue.state.collect { state ->
                val source = state.source
                if (source == null || source.phase == QueueSourcePhase.DETACHED) {
                    cancelWorker()
                    runtime = null
                    enabled = false
                } else if (source.sessionId != runtime?.sessionId) {
                    cancelWorker()
                    runtime = null
                    val seed = seeds.remove(source.sessionId)
                    if (seed != null) {
                        enabled = true
                        runtime =
                            SourceRuntime(
                                source.sessionId,
                                seed.rows.drop(source.acceptedRows.size),
                                seed.next,
                            )
                        if (ready) start(requireNotNull(runtime))
                    } else if (
                        state.playWhenReady &&
                            source.hasMore &&
                            source.phase != QueueSourcePhase.CHANGED
                    ) {
                        enabled = true
                        runtime =
                            SourceRuntime(source.sessionId, emptyList(), null, needsRecovery = true)
                        if (ready) start(requireNotNull(runtime))
                    }
                }
            }
        }
    }

    suspend fun play(request: PlaylistPlaybackRequest): AppResult<Unit> {
        if (closed) return unavailable()
        val playable = request.rows.filter { it.track != null && !it.unavailable }
        val selected =
            if (request.shuffle)
                playable.takeIf { it.isNotEmpty() }?.let { it[queue.randomIndex(it.size)] }
            else request.rows.getOrNull(request.selectedOrdinal)
        if (
            request.rows.withIndex().any { it.value.identity.ordinal != it.index } ||
                selected?.track == null ||
                selected.unavailable ||
                request.ref.source.kind != "playlist"
        )
            return AppResult.Failure(
                AppError(ErrorCode.INVALID_CONFIG, "queue.source.request", false)
            )
        val generation = ++requestGeneration
        val sessionId = Uuid.random().toString()
        val frozen =
            request.copy(rows = request.rows.toList(), selectedOrdinal = selected.identity.ordinal)
        return scope
            .async {
                if (closed || generation != requestGeneration)
                    throw CancellationException("Superseded playlist request")
                seeds[sessionId] = frozen
                try {
                    val source =
                        QueueSourceState(
                            sessionId,
                            frozen.ref,
                            frozen.selectedOrdinal,
                            (if (frozen.shuffle) frozen.rows
                                else frozen.rows.take(frozen.selectedOrdinal + 1))
                                .map { it.identity },
                            shuffleRequested = frozen.shuffle,
                        )
                    if (frozen.shuffle) {
                        val entries = playable.map { row ->
                            QueueEntry(
                                QueueEntryId(Uuid.random().toString()),
                                requireNotNull(row.track).id,
                            )
                        }
                        commands.playSourceList(
                            playable.map { requireNotNull(it.track) },
                            entries,
                            entries[playable.indexOf(selected)].id,
                            source,
                        ) {
                            !closed && generation == requestGeneration
                        }
                    } else
                        commands.playSource(
                            selected.track,
                            QueueEntry(QueueEntryId(Uuid.random().toString()), selected.track.id),
                            source,
                        ) {
                            !closed && generation == requestGeneration
                        }
                } finally {
                    if (queue.state.value.source?.sessionId != sessionId) seeds.remove(sessionId)
                }
            }
            .await()
    }

    suspend fun retry(): AppResult<Unit> {
        if (closed || !ready) return unavailable()
        val source = queue.state.value.source ?: return unavailable()
        if (source.phase == QueueSourcePhase.CHANGED)
            return AppResult.Failure(
                source.error ?: AppError(ErrorCode.RESOURCE_CHANGED, "queue.source.changed", false)
            )
        if (!source.hasMore && source.error == null) return AppResult.Success(Unit)
        val current = currentRuntime(source)
        enabled = true
        if (worker?.isActive == true) return AppResult.Success(Unit)
        return start(current).await()
    }

    fun resumeIfNeeded() {
        if (closed) return
        val source = queue.state.value.source ?: return
        if (source.phase in setOf(QueueSourcePhase.CHANGED, QueueSourcePhase.DETACHED)) return
        if (!source.hasMore && runtime?.staged == null) return
        enabled = true
        if (ready) start(currentRuntime(source))
    }

    private fun currentRuntime(source: QueueSourceState): SourceRuntime =
        runtime?.takeIf { it.sessionId == source.sessionId }
            ?: SourceRuntime(
                    source.sessionId,
                    emptyList(),
                    null,
                    needsRecovery = source.phase != QueueSourcePhase.COMPLETE,
                )
                .also {
                    if (source.phase == QueueSourcePhase.COMPLETE) {
                        it.staged =
                            StagedPage(
                                source.acceptedRows.size,
                                emptyList(),
                                emptyList(),
                                null,
                                complete = true,
                            )
                    }
                    runtime = it
                }

    fun stop() {
        ++requestGeneration
        enabled = false
        seeds.clear()
        cancelWorker()
        runtime = null
    }

    fun networkInvalidated() {
        ready = false
        cancelWorker()
        runtime = null
    }

    fun networkActivated() {
        ready = true
        if (enabled) resumeIfNeeded()
    }

    fun close() {
        if (!closed) {
            closed = true
            stop()
            scope.cancel()
        }
    }

    private fun cancelWorker() {
        ++sourceEpoch
        worker?.cancel()
        worker = null
    }

    private fun start(current: SourceRuntime): Deferred<AppResult<Unit>> {
        worker
            ?.takeIf { it.isActive }
            ?.let {
                return it
            }
        val epoch = ++sourceEpoch
        return scope.async { work(current, epoch) }.also { worker = it }
    }

    private suspend fun guard(current: SourceRuntime, epoch: Long) {
        currentCoroutineContext().ensureActive()
        if (
            closed ||
                !ready ||
                epoch != sourceEpoch ||
                queue.state.value.source?.sessionId != current.sessionId ||
                queue.state.value.source?.phase == QueueSourcePhase.DETACHED
        )
            throw CancellationException("Queue source changed")
    }

    private suspend fun work(current: SourceRuntime, epoch: Long): AppResult<Unit> {
        return try {
            load(current, epoch)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fail(current, epoch, AppError(ErrorCode.UNKNOWN, "queue.source.load", false))
        }
    }

    private suspend fun load(current: SourceRuntime, epoch: Long): AppResult<Unit> {
        guard(current, epoch)
        queue.dispatch(QueueCommand.SourceReady(current.sessionId))
        if (current.needsRecovery) {
            val recovered = recover(current, epoch)
            if (recovered is AppResult.Failure) return recovered
        }
        var cursorRecovered = false
        loadLoop@ while (true) {
            guard(current, epoch)
            val source = requireNotNull(queue.state.value.source)
            if (!source.hasMore && current.staged == null) return AppResult.Success(Unit)
            val stage =
                current.staged
                    ?: run {
                        val page =
                            if (current.seedTail.isNotEmpty())
                                CatalogPage(current.seedTail, current.cursor)
                            else if (current.cursor == null) CatalogPage(emptyList(), null)
                            else {
                                when (
                                    val result =
                                        read(current, epoch, requireNotNull(current.cursor))
                                ) {
                                    is AppResult.Success -> result.value
                                    is AppResult.Failure -> {
                                        if (
                                            result.error.operationId == "catalog.cursor" &&
                                                !cursorRecovered
                                        ) {
                                            cursorRecovered = true
                                            val recovered = recover(current, epoch)
                                            if (recovered is AppResult.Failure) return recovered
                                            continue@loadLoop
                                        }
                                        return fail(current, epoch, result.error)
                                    }
                                }
                            }
                        guard(current, epoch)
                        StagedPage(
                                source.acceptedRows.size,
                                page.items.map { row ->
                                    PlaylistQueueRow(
                                        row.identity,
                                        row.track
                                            ?.takeUnless { row.unavailable }
                                            ?.let {
                                                QueueEntry(
                                                    QueueEntryId(Uuid.random().toString()),
                                                    it.id,
                                                )
                                            },
                                    )
                                },
                                page.items.mapNotNull { it.track }.distinctBy { it.id },
                                page.next,
                                page.next == null,
                            )
                            .also { current.staged = it }
                    }
            val saved = remember(stage.tracks)
            guard(current, epoch)
            if (saved is AppResult.Failure) return fail(current, epoch, saved.error)
            try {
                queue.dispatch(
                    QueueCommand.AppendSourcePage(
                        current.sessionId,
                        stage.from,
                        stage.rows,
                        stage.complete,
                    )
                ) {
                    !closed &&
                        ready &&
                        epoch == sourceEpoch &&
                        queue.state.value.source?.sessionId == current.sessionId
                }
            } catch (_: CancellationException) {
                // Replacing an automatic audio selection does not invalidate an accepted page.
                guard(current, epoch)
            }
            guard(current, epoch)
            val accepted = queue.state.value
            if (accepted.source?.phase == QueueSourcePhase.CHANGED)
                return AppResult.Failure(
                    accepted.source.error
                        ?: AppError(ErrorCode.PROVIDER_CHANGED, "queue.source.page", false)
                )
            val persisted = commit(accepted, stage.tracks)
            guard(current, epoch)
            if (persisted is AppResult.Failure) return fail(current, epoch, persisted.error)
            current.staged = null
            cursorRecovered = false
            current.seedTail = emptyList()
            current.cursor = stage.next
            if (stage.complete) return AppResult.Success(Unit)
        }
    }

    private suspend fun recover(current: SourceRuntime, epoch: Long): AppResult<Unit> {
        guard(current, epoch)
        val saved = requireNotNull(queue.state.value.source).acceptedRows.toList()
        val head = catalog.playlist(requireNotNull(queue.state.value.source).ref, refresh = true)
        guard(current, epoch)
        var page =
            when (head) {
                is AppResult.Success -> head.value.tracks
                is AppResult.Failure -> return fail(current, epoch, head.error)
            }
        val replay = mutableListOf<PlaylistOccurrence>()
        val cursors = mutableSetOf<CatalogCursor>()
        while (true) {
            replay += page.items
            if (replay.size >= saved.size) break
            val next = page.next ?: return changed(current, epoch)
            if (!cursors.add(next)) return changed(current, epoch)
            page =
                when (val result = read(current, epoch, next)) {
                    is AppResult.Success -> result.value
                    is AppResult.Failure -> return fail(current, epoch, result.error)
                }
        }
        if (!PlaylistResumeVerifier.matches(saved, replay.take(saved.size).map { it.identity }))
            return changed(current, epoch)
        guard(current, epoch)
        current.seedTail = replay.drop(saved.size)
        current.cursor = page.next
        current.needsRecovery = false
        return AppResult.Success(Unit)
    }

    private suspend fun changed(current: SourceRuntime, epoch: Long): AppResult<Unit> {
        guard(current, epoch)
        val error = AppError(ErrorCode.RESOURCE_CHANGED, "queue.source.changed", false)
        queue.dispatch(QueueCommand.SourceFailed(current.sessionId, error, changed = true))
        return AppResult.Failure(error)
    }

    private suspend fun read(
        current: SourceRuntime,
        epoch: Long,
        cursor: CatalogCursor,
    ): AppResult<CatalogPage<PlaylistOccurrence>> {
        var attempt = 0
        while (true) {
            guard(current, epoch)
            val result = catalog.morePlaylist(cursor)
            guard(current, epoch)
            val error = result.errorOrNull() ?: return result
            val retryable =
                error.retryable &&
                    (error.code != ErrorCode.RATE_LIMIT || error.retryAfterMs != null)
            if (!retryable || attempt >= 2) return result
            delay(
                maxOf(
                    if (attempt++ == 0) 1000L else 3000L,
                    error.retryAfterMs?.coerceAtLeast(0) ?: 0,
                )
            )
        }
    }

    private suspend fun fail(
        current: SourceRuntime,
        epoch: Long,
        error: AppError,
    ): AppResult<Unit> {
        guard(current, epoch)
        queue.dispatch(QueueCommand.SourceFailed(current.sessionId, error))
        return AppResult.Failure(error)
    }

    private fun unavailable(): AppResult<Unit> =
        AppResult.Failure(AppError(ErrorCode.SOURCE_UNAVAILABLE, "queue.source.unavailable", true))
}
