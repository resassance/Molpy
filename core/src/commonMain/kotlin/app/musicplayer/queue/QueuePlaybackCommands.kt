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

    suspend fun playList(tracks: List<Track>, startIndex: Int): AppResult<Unit> {
        if (startIndex !in tracks.indices)
            return AppResult.Failure(AppError(ErrorCode.INVALID_CONFIG, "queue.list", false))
        invalidateQueue()
        val selected = generation
        val frozen = tracks.toList()
        if (rememberList != null) {
            val result = rememberList.invoke(frozen)
            checkCurrent(selected)
            if (result is AppResult.Failure) return result
        } else
            for (track in frozen) {
                val result = remember(track)
                checkCurrent(selected)
                if (result is AppResult.Failure) return result
            }
        val entries = frozen.map { QueueEntry(QueueEntryId(Uuid.random().toString()), it.id) }
        return queue.replace(entries, entries[startIndex].id) { selected == generation }
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

    private suspend fun checkCurrent(selected: Long) {
        currentCoroutineContext().ensureActive()
        if (selected != generation) throw CancellationException("Superseded queue command")
    }
}
