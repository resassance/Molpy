package app.musicplayer.ui.player

import app.musicplayer.model.*
import app.musicplayer.queue.QueueOrder

data class QueueItem(val entryId: QueueEntryId, val track: Track?)

data class QueuePresentation(
    val previous: QueueItem?,
    val current: QueueItem?,
    val next: QueueItem?,
    val upcoming: List<QueueItem>,
)

fun presentQueue(queue: QueueSnapshot, tracks: Map<TrackId, Track>): QueuePresentation {
    fun item(entry: QueueEntry?) = entry?.let { QueueItem(it.id, tracks[it.trackId]) }
    return QueuePresentation(
        item(QueueOrder.previous(queue)),
        item(queue.entries.firstOrNull { it.id == queue.currentEntryId }),
        item(QueueOrder.next(queue)),
        QueueOrder.upcoming(queue).map { QueueItem(it.id, tracks[it.trackId]) },
    )
}
