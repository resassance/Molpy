package app.musicplayer.ui.player

import app.musicplayer.model.*

data class QueueItem(val entryId: QueueEntryId, val track: Track?)

data class QueuePresentation(
    val previous: QueueItem?,
    val current: QueueItem?,
    val next: QueueItem?,
    val upcoming: List<QueueItem>,
)

fun presentQueue(queue: QueueSnapshot, tracks: Map<TrackId, Track>): QueuePresentation {
    val index = queue.entries.indexOfFirst { it.id == queue.currentEntryId }
    fun item(position: Int) =
        queue.entries.getOrNull(position)?.let { QueueItem(it.id, tracks[it.trackId]) }
    return QueuePresentation(
        if (index > 0) item(index - 1) else null,
        if (index >= 0) item(index) else null,
        if (index >= 0) item(index + 1) else null,
        queue.entries.drop(if (index >= 0) index + 1 else 0).map {
            QueueItem(it.id, tracks[it.trackId])
        },
    )
}
