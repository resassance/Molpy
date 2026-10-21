package app.musicplayer.queue

import app.musicplayer.model.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class QueuePlaybackCommands(
    private val queue: QueueController,
    private val remember: suspend (Track) -> AppResult<Unit>,
) {
    private var generation = 0L

    suspend fun playList(tracks: List<Track>, startIndex: Int): AppResult<Unit> {
        if (startIndex !in tracks.indices)
            return AppResult.Failure(AppError(ErrorCode.INVALID_CONFIG, "queue.list", false))
        val selected = ++generation
        val frozen = tracks.toList()
        for (track in frozen) {
            val result = remember(track)
            checkCurrent(selected)
            if (result is AppResult.Failure) return result
        }
        val entries = frozen.map { QueueEntry(QueueEntryId(Uuid.random().toString()), it.id) }
        return queue.replace(entries, entries[startIndex].id) { selected == generation }
    }

    suspend fun enqueue(track: Track): AppResult<Unit> {
        val selected = ++generation
        val saved = remember(track)
        checkCurrent(selected)
        if (saved is AppResult.Failure) return saved
        val entry = QueueEntry(QueueEntryId(Uuid.random().toString()), track.id)
        queue.dispatch(QueueCommand.Add(listOf(entry))) { selected == generation }
        return AppResult.Success(Unit)
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
        invalidatePending()
        queue.dispatch(command)
    }

    fun invalidatePending() {
        ++generation
    }

    private suspend fun checkCurrent(selected: Long) {
        currentCoroutineContext().ensureActive()
        if (selected != generation) throw CancellationException("Superseded queue command")
    }
}
