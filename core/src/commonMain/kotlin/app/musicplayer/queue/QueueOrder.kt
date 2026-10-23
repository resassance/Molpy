package app.musicplayer.queue

import app.musicplayer.model.*

object QueueOrder {
    fun isValid(snapshot: QueueSnapshot): Boolean {
        val order = snapshot.traversal ?: return false
        val ids = snapshot.entries.map { it.id }.toSet()
        return ids.size == snapshot.entries.size &&
            (snapshot.currentEntryId == null || snapshot.currentEntryId in ids) &&
            order.history.all { it in ids } &&
            order.upcoming.all { it in ids } &&
            order.upcoming.distinct().size == order.upcoming.size &&
            snapshot.currentEntryId !in order.upcoming &&
            order.priority == order.upcoming.take(order.priority.size) &&
            order.priority.size <= order.upcoming.size
    }

    fun normalized(snapshot: QueueSnapshot): QueueSnapshot {
        if (isValid(snapshot)) return snapshot
        val entries = snapshot.entries.distinctBy { it.id }
        val index = entries.indexOfFirst { it.id == snapshot.currentEntryId }
        return snapshot.copy(
            entries = entries,
            currentEntryId = snapshot.currentEntryId.takeIf { index >= 0 },
            positionMs = if (index >= 0) snapshot.positionMs.coerceAtLeast(0) else 0,
            shuffleEnabled = false,
            traversal =
                QueueTraversal(
                    upcoming = entries.drop(if (index >= 0) index + 1 else 0).map { it.id }
                ),
        )
    }

    fun previous(snapshot: QueueSnapshot): QueueEntry? {
        val q = normalized(snapshot)
        val id = q.traversal!!.history.lastOrNull()
        return q.entries.firstOrNull { it.id == id }
    }

    fun hasDeferredContinuation(snapshot: QueueSnapshot): Boolean = snapshot.source?.hasMore == true

    fun next(snapshot: QueueSnapshot): QueueEntry? =
        upcoming(snapshot).firstOrNull()?.takeUnless {
            hasDeferredContinuation(snapshot) && it.id in snapshot.source!!.deferredEndIds
        }

    fun upcoming(snapshot: QueueSnapshot): List<QueueEntry> {
        val q = normalized(snapshot)
        val entries = q.entries.associateBy { it.id }
        return q.traversal!!.upcoming.mapNotNull(entries::get)
    }
}
