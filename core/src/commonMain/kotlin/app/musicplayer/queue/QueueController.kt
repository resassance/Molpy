package app.musicplayer.queue

import app.musicplayer.model.*
import app.musicplayer.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Service-owned queue. Commands and [onPlayback] run on the engine owner's dispatcher.
 * The service collects coordinator callbacks into [onPlayback] for its own lifetime.
 * Only short state/effect setup is serialized: lookup/resolve never holds the command lock.
 */
class QueueController(
    private val engine: PlayerEngine,
    private val coordinator: PlaybackCoordinator,
    private val lookup: suspend (TrackId) -> Track?,
) {
    private val mutableState = MutableStateFlow(QueueSnapshot())
    val state: StateFlow<QueueSnapshot> = mutableState.asStateFlow()
    private val mutableSnapshot = MutableStateFlow(PlaybackSnapshot())
    val snapshot: StateFlow<PlaybackSnapshot> = mutableSnapshot.asStateFlow()
    private val mutex = Mutex()
    private var generation = 0L
    private var selection: Deferred<AppResult<Unit>>? = null

    private var restoredPosition = false

    /** Install durable metadata while paused. No resolver or engine load runs before explicit Play. */
    suspend fun restore(snapshot: QueueSnapshot) = mutex.withLock {
        cancelSelection()
        coordinator.stop()
        val entries = snapshot.entries.distinctBy { it.id }.toList()
        val current = entries.firstOrNull { it.id == snapshot.currentEntryId } ?: entries.firstOrNull()
        val position = if (current?.id == snapshot.currentEntryId) snapshot.positionMs.coerceAtLeast(0) else 0
        mutableState.value = QueueSnapshot(entries, current?.id, position, false)
        val track = current?.let { lookup(it.trackId) }
        mutableSnapshot.value = if (current == null) PlaybackSnapshot() else
            PlaybackSnapshot(PlayerStatus.PAUSED, current, track, position, playWhenReady = false)
        restoredPosition = current != null
    }

    suspend fun dispatch(command: QueueCommand) { transition(command) }

    /** Bridge for the existing PlaybackPort: remember metadata in the service lookup before calling. */
    suspend fun select(entry: QueueEntry, positionMs: Long, playWhenReady: Boolean): AppResult<Unit> =
        transition(QueueCommand.Select(entry.id), entry, positionMs, playWhenReady)

    private suspend fun transition(
        command: QueueCommand,
        addedEntry: QueueEntry? = null,
        positionMs: Long = 0,
        playWhenReady: Boolean? = null,
    ): AppResult<Unit> = coroutineScope {
        val pending = mutex.withLock {
            var before = mutableState.value
            if (addedEntry != null) {
                val existing = before.entries.find { it.id == addedEntry.id }
                require(existing == null || existing == addedEntry) { "Queue entry identity cannot change Track" }
                if (existing == null) before = QueueReducer.reduce(before, QueueCommand.Add(listOf(addedEntry))).snapshot
            }
            if (command is QueueCommand.Select && before.entries.none { it.id == command.id }) return@withLock null
            val reduced = QueueReducer.reduce(before, command)
            val after = if (playWhenReady == null) reduced.snapshot else reduced.snapshot.copy(
                positionMs = positionMs.coerceAtLeast(0), playWhenReady = playWhenReady,
            )
            mutableState.value = after
            when {
                reduced.stopPlayback -> { restoredPosition = false; cancelSelection(); coordinator.stop(); mutableSnapshot.value = PlaybackSnapshot(); null }
                after.currentEntryId != null && (after.currentEntryId != before.currentEntryId || command is QueueCommand.Select ||
                    (reduced.seekToMs != null && engine.snapshot.value.current?.id != after.currentEntryId)) -> {
                    restoredPosition = false
                    cancelSelection()
                    coordinator.stop()
                    val selectedGeneration = generation
                    val entry = after.entries.first { it.id == after.currentEntryId }
                    mutableSnapshot.value = PlaybackSnapshot(PlayerStatus.RESOLVING, entry, positionMs = after.positionMs,
                        playWhenReady = after.playWhenReady)
                    // Starts lookup/resolve on the owner's dispatcher, but does not await it under the lock.
                    async(start = CoroutineStart.UNDISPATCHED) {
                        val track = lookup(entry.trackId)
                        ensureActive()
                        if (selectedGeneration != generation) throw CancellationException("Superseded queue selection")
                        if (track == null) {
                            val error = AppError(ErrorCode.SOURCE_UNAVAILABLE, "queue.lookup", false)
                            mutableSnapshot.value = mutableSnapshot.value.copy(status = PlayerStatus.FAILED, error = error)
                            AppResult.Failure(error)
                        } else {
                            val result = coordinator.select(track, entry, mutableState.value.positionMs, mutableState.value.playWhenReady)
                            if (selectedGeneration == generation) applyPlayback(coordinator.snapshot.value)
                            result
                        }
                    }.also { selection = it }
                }
                reduced.seekToMs != null -> {
                    coordinator.seek(after.positionMs)
                    mutableSnapshot.value = mutableSnapshot.value.copy(positionMs = after.positionMs)
                    null
                }
                else -> null
            }
        }
        pending?.await() ?: AppResult.Success(Unit)
    }

    /** Stale callbacks cannot mutate position/intent or advance a newer occurrence. */
    suspend fun onPlayback(actual: PlaybackSnapshot) {
        val ended = mutex.withLock {
            val active = coordinator.snapshot.value
            if (actual.current?.id != mutableState.value.currentEntryId || actual.current == null ||
                actual.current != active.current || actual.loadId != active.loadId) return@withLock null
            applyPlayback(actual)
            actual.current.id.takeIf { actual.status == PlayerStatus.ENDED }
        }
        if (ended != null) {
            try {
                dispatch(QueueCommand.Ended(ended))
            } catch (_: CancellationException) {
                // Interrupted selection work must not cancel collection; owner shutdown still propagates.
                currentCoroutineContext().ensureActive()
            }
        }
    }

    private fun applyPlayback(actual: PlaybackSnapshot) {
        if (actual.current == null || actual.current.id != mutableState.value.currentEntryId) return
        mutableSnapshot.value = actual
        mutableState.value = mutableState.value.copy(positionMs = actual.positionMs, playWhenReady = actual.playWhenReady)
    }

    suspend fun play() {
        val restart = mutex.withLock {
            val current = mutableState.value.currentEntryId ?: return@withLock null
            if (engine.snapshot.value.current?.id != current && mutableSnapshot.value.status != PlayerStatus.RESOLVING) {
                return@withLock mutableState.value.entries.first { it.id == current }
            }
            coordinator.play()
            mutableState.value = mutableState.value.copy(playWhenReady = true)
            mutableSnapshot.value = mutableSnapshot.value.copy(playWhenReady = true)
            null
        }
        if (restart != null) {
            val position = if (restoredPosition) mutableState.value.positionMs else 0
            restoredPosition = false
            select(restart, position, true)
        }
    }
    suspend fun pause() = mutex.withLock {
        coordinator.pause()
        mutableState.value = mutableState.value.copy(playWhenReady = false)
        mutableSnapshot.value = mutableSnapshot.value.copy(playWhenReady = false)
    }
    suspend fun seek(positionMs: Long) = mutex.withLock {
        if (mutableState.value.currentEntryId == null) return@withLock
        val clamped = positionMs.coerceAtLeast(0).let { value -> mutableSnapshot.value.durationMs?.let(value::coerceAtMost) ?: value }
        coordinator.seek(clamped)
        mutableState.value = mutableState.value.copy(positionMs = clamped)
        mutableSnapshot.value = mutableSnapshot.value.copy(positionMs = clamped)
    }
    /** The explicit stop checkpoint is serialized before state clearing and later user commands. */
    suspend fun stop(checkpoint: suspend (QueueSnapshot) -> Unit = {}) = mutex.withLock {
        coordinator.pause()
        mutableState.value = mutableState.value.copy(playWhenReady = false)
        mutableSnapshot.value = mutableSnapshot.value.copy(playWhenReady = false)
        checkpoint(mutableState.value)
        restoredPosition = false
        cancelSelection()
        coordinator.stop()
        mutableState.value = mutableState.value.copy(playWhenReady = false)
        mutableSnapshot.value = PlaybackSnapshot()
    }
    private fun cancelSelection() { ++generation; selection?.cancel(); selection = null }
}
