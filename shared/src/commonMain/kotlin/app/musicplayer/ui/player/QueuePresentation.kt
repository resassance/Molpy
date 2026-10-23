package app.musicplayer.ui.player

import app.musicplayer.catalog.PublicPlaylistRef
import app.musicplayer.model.*
import app.musicplayer.queue.QueueOrder

data class QueueItem(val entryId: QueueEntryId, val track: Track?)

data class QueueSourceGap(
    val beforeIndex: Int,
    val phase: QueueSourcePhase,
    val waiting: Boolean,
    val error: AppError?,
    val canRetry: Boolean,
    val reopen: PublicPlaylistRef?,
)

data class QueuePresentation(
    val previous: QueueItem?,
    val current: QueueItem?,
    val next: QueueItem?,
    val upcoming: List<QueueItem>,
    val gap: QueueSourceGap? = null,
    val shufflePending: Boolean = false,
    val sourceRecoveryError: AppError? = null,
    val sourceError: AppError? = null,
    val sourceCanRetry: Boolean = false,
    val canRequestNext: Boolean = next != null,
)

fun presentQueue(queue: QueueSnapshot, tracks: Map<TrackId, Track>): QueuePresentation {
    fun item(entry: QueueEntry?) = entry?.let { QueueItem(it.id, tracks[it.trackId]) }
    val upcoming = QueueOrder.upcoming(queue).map { QueueItem(it.id, tracks[it.trackId]) }
    val source = queue.source?.takeIf { it.hasMore }
    val sourceError = queue.source?.takeUnless { it.phase == QueueSourcePhase.DETACHED }?.error
    val sourceCanRetry = sourceError != null && queue.source?.phase != QueueSourcePhase.CHANGED
    val next = item(QueueOrder.next(queue))
    val gap = source?.let {
        QueueSourceGap(
            beforeIndex =
                upcoming.indexOfFirst { it.entryId in source.deferredEndIds }.takeIf { it >= 0 }
                    ?: upcoming.size,
            phase = source.phase,
            waiting = source.waiting,
            error = source.error,
            canRetry = sourceCanRetry,
            reopen = source.ref.takeIf { source.phase == QueueSourcePhase.CHANGED },
        )
    }
    return QueuePresentation(
        previous = item(QueueOrder.previous(queue)),
        current = item(queue.entries.firstOrNull { it.id == queue.currentEntryId }),
        next = next,
        upcoming = upcoming,
        gap = gap,
        shufflePending = source?.shuffleRequested == true,
        sourceRecoveryError = queue.sourceRecoveryError,
        sourceError = sourceError,
        sourceCanRetry = sourceCanRetry,
        canRequestNext = next != null || source != null,
    )
}

fun canMoveQueueEntry(queue: QueueSnapshot, id: QueueEntryId, toIndex: Int): Boolean {
    val upcoming = QueueOrder.upcoming(queue)
    val target = upcoming.getOrNull(toIndex) ?: return false
    if (upcoming.none { it.id == id }) return false
    val source = queue.source?.takeIf { it.hasMore } ?: return true
    return (id in source.deferredEndIds) == (target.id in source.deferredEndIds)
}

internal fun queueSourceMessage(gap: QueueSourceGap): String {
    if (gap.phase != QueueSourcePhase.CHANGED && gap.error != null)
        return if (gap.error.code == ErrorCode.DATABASE) "Не удалось сохранить продолжение очереди"
        else "Не удалось загрузить продолжение плейлиста"
    return when (gap.phase) {
        QueueSourcePhase.LOADING ->
            if (gap.waiting) "Ожидаем продолжение плейлиста…"
            else "Загружаем продолжение плейлиста…"
        QueueSourcePhase.FAILED -> "Не удалось загрузить продолжение плейлиста"
        QueueSourcePhase.CHANGED -> "Плейлист изменился или недоступен. Откройте его заново"
        QueueSourcePhase.COMPLETE,
        QueueSourcePhase.DETACHED -> "Продолжение плейлиста загружено"
    }
}
