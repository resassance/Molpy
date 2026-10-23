package app.musicplayer.storage

import app.musicplayer.model.*
import app.musicplayer.queue.QueueOrder
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object QueueRecordMapper {
    @Serializable
    private data class Payload(
        val version: Int,
        val history: List<String>,
        val upcoming: List<String>,
        val priority: List<String>,
    )

    fun encode(snapshot: QueueSnapshot): String {
        val order = QueueOrder.normalized(snapshot).traversal!!
        return Json.encodeToString(
            Payload(
                1,
                order.history.map { it.value },
                order.upcoming.map { it.value },
                order.priority.map { it.value },
            )
        )
    }

    fun restore(entries: List<QueueEntry>, saved: QueueSnapshotRecord): QueueSnapshot {
        val base =
            QueueSnapshot(
                entries,
                saved.currentEntryId?.let(::QueueEntryId),
                saved.positionMs.coerceAtLeast(0),
                false,
            )
        val order =
            saved.traversalJson?.let { raw ->
                try {
                    val payload = Json.decodeFromString<Payload>(raw)
                    if (payload.version != 1) null
                    else
                        QueueTraversal(
                            payload.history.map(::QueueEntryId),
                            payload.upcoming.map(::QueueEntryId),
                            payload.priority.map(::QueueEntryId),
                        )
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        return QueueOrder.normalized(
            base.copy(shuffleEnabled = saved.shuffleEnabled, traversal = order)
        )
    }

    fun prune(snapshot: QueueSnapshot, available: Set<TrackId>): QueueSnapshot {
        val entries = snapshot.entries.filter { it.trackId in available }
        val ids = entries.map { it.id }.toSet()
        val order = snapshot.traversal!!
        val pending = order.upcoming.filter { it in ids }
        val current =
            snapshot.currentEntryId?.takeIf { it in ids }
                ?: pending.firstOrNull()
                ?: entries.firstOrNull()?.id
        val upcoming = pending.filterNot { it == current }
        return snapshot.copy(
            entries = entries,
            currentEntryId = current,
            positionMs = if (current == snapshot.currentEntryId) snapshot.positionMs else 0,
            playWhenReady = false,
            traversal =
                QueueTraversal(
                    order.history.filter { it in ids },
                    upcoming,
                    order.priority.filter { it in upcoming },
                ),
        )
    }
}
