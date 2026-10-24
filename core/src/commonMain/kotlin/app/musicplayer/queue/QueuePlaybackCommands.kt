package app.musicplayer.queue

import app.musicplayer.model.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class QueuePlaybackCommands(
    private val queue: QueueController,
    private val remember: suspend (Track) -> AppResult<Unit>,
    private val rememberList: (suspend (List<Track>) -> AppResult<Unit>)?,
) {
    constructor(
        queue: QueueController,
        remember: suspend (Track) -> AppResult<Unit>,
    ) : this(queue, remember, null)

    private var generation = 0L
    private val insertions = QueueInsertionGate()

    suspend fun playSource(
        track: Track,
        entry: QueueEntry,
        source: QueueSourceState,
        isCurrent: () -> Boolean = { true },
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("Superseded playlist request")
        invalidateQueue()
        val selected = generation
        val saved = remember(track)
        checkCurrent(selected)
        if (!isCurrent()) throw CancellationException("Superseded playlist request")
        if (saved is AppResult.Failure) return saved
        return queue.replace(listOf(entry), entry.id, source) {
            selected == generation && isCurrent()
        }
    }

    internal suspend fun playSourceList(
        tracks: List<Track>,
        entries: List<QueueEntry>,
        currentEntryId: QueueEntryId,
        source: QueueSourceState,
        isCurrent: () -> Boolean,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("Superseded playlist request")
        invalidateQueue()
        val selected = generation
        val saved = rememberTracks(tracks, selected)
        if (!isCurrent()) throw CancellationException("Superseded playlist request")
        if (saved is AppResult.Failure) return saved
        return queue.replace(entries, currentEntryId, source, shuffleEnabled = true) {
            selected == generation && isCurrent()
        }
    }

    suspend fun playList(tracks: List<Track>, startIndex: Int): AppResult<Unit> =
        installList(tracks, startIndex, shuffleEnabled = null)

    suspend fun playShuffledList(tracks: List<Track>): AppResult<Unit> =
        installList(tracks, 0, shuffleEnabled = true)

    private suspend fun installList(
        tracks: List<Track>,
        startIndex: Int,
        shuffleEnabled: Boolean?,
    ): AppResult<Unit> {
        if (startIndex !in tracks.indices)
            return AppResult.Failure(AppError(ErrorCode.INVALID_CONFIG, "queue.list", false))
        invalidateQueue()
        val selected = generation
        val frozen = tracks.toList()
        val saved = rememberTracks(frozen, selected)
        if (saved is AppResult.Failure) return saved
        val entries = frozen.map { QueueEntry(QueueEntryId(Uuid.random().toString()), it.id) }
        return queue.replace(entries, entries[startIndex].id, shuffleEnabled = shuffleEnabled) {
            selected == generation
        }
    }

    suspend fun enqueue(
        track: Track,
        position: QueueInsertion = QueueInsertion.END,
    ): AppResult<Unit> {
        invalidatePending()
        return insertions.run { check ->
            val saved = remember(track)
            check()
            if (saved is AppResult.Failure) return@run saved
            val entry = QueueEntry(QueueEntryId(Uuid.random().toString()), track.id)
            queue.dispatch(QueueCommand.Add(listOf(entry), position)) {
                check()
                true
            }
            AppResult.Success(Unit)
        }
    }

    suspend fun select(
        track: Track,
        entry: QueueEntry,
        positionMs: Long,
        playWhenReady: Boolean,
    ): AppResult<Unit> {
        require(track.id == entry.trackId)
        val selected = ++generation
        val saved = remember(track)
        checkCurrent(selected)
        return if (saved is AppResult.Failure) saved
        else queue.select(entry, positionMs, playWhenReady) { selected == generation }
    }

    suspend fun dispatch(command: QueueCommand) {
        when (command) {
            is QueueCommand.Replace -> invalidateQueue()
            is QueueCommand.SetShuffle,
            QueueCommand.ToggleShuffle -> Unit
            else -> invalidatePending()
        }
        queue.dispatch(command)
    }

    fun invalidatePending() {
        ++generation
    }

    fun invalidateQueue() {
        invalidatePending()
        insertions.invalidate()
    }

    private suspend fun rememberTracks(tracks: List<Track>, selected: Long): AppResult<Unit> {
        if (rememberList != null) {
            val result = rememberList.invoke(tracks)
            checkCurrent(selected)
            return result
        }
        for (track in tracks) {
            val result = remember(track)
            checkCurrent(selected)
            if (result is AppResult.Failure) return result
        }
        return AppResult.Success(Unit)
    }

    private suspend fun checkCurrent(selected: Long) {
        currentCoroutineContext().ensureActive()
        if (selected != generation) throw CancellationException("Superseded queue command")
    }
}
