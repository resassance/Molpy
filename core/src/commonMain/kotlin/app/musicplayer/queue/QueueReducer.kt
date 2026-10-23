package app.musicplayer.queue

import app.musicplayer.model.*
import kotlin.random.Random

sealed interface QueueCommand {
    data class MoveUpcoming(val id: QueueEntryId, val toIndex: Int) : QueueCommand

    data class Replace(val entries: List<QueueEntry>, val currentEntryId: QueueEntryId) :
        QueueCommand

    data class Add(
        val entries: List<QueueEntry>,
        val position: QueueInsertion = QueueInsertion.END,
    ) : QueueCommand

    data class Remove(val id: QueueEntryId) : QueueCommand

    data class Select(val id: QueueEntryId) : QueueCommand

    data class SetShuffle(val enabled: Boolean) : QueueCommand

    data object ToggleShuffle : QueueCommand

    data object Next : QueueCommand

    data object Previous : QueueCommand

    data class Ended(val entryId: QueueEntryId) : QueueCommand
}

data class QueueTransition(
    val snapshot: QueueSnapshot,
    val seekToMs: Long? = null,
    val stopPlayback: Boolean = false,
)

object QueueReducer {
    fun reduce(
        snapshot: QueueSnapshot,
        command: QueueCommand,
        random: Random = Random.Default,
    ): QueueTransition {
        val q = QueueOrder.normalized(snapshot)
        val order = q.traversal!!
        fun select(id: QueueEntryId): QueueTransition {
            val index = order.upcoming.indexOf(id)
            val upcoming =
                if (index >= 0) order.upcoming.drop(index + 1)
                else order.upcoming.filterNot { it == id }
            return QueueTransition(
                q.copy(
                    currentEntryId = id,
                    positionMs = 0,
                    traversal =
                        order.copy(
                            history =
                                order.history +
                                    listOfNotNull(q.currentEntryId?.takeIf { it != id }),
                            upcoming = upcoming,
                            priority = order.priority.filter { it in upcoming },
                        ),
                ),
                seekToMs = 0,
            )
        }
        fun next(): QueueTransition =
            when {
                q.currentEntryId == null -> QueueTransition(snapshot)
                order.upcoming.isNotEmpty() -> select(order.upcoming.first())
                else -> QueueTransition(q.copy(playWhenReady = false), stopPlayback = true)
            }
        fun shuffle(enabled: Boolean): QueueTransition {
            if (enabled == q.shuffleEnabled) return QueueTransition(snapshot)
            val eligible = order.upcoming.drop(order.priority.size)
            val sorted =
                if (enabled) eligible.shuffled(random)
                else {
                    val remaining = eligible.toSet()
                    q.entries.map { it.id }.filter { it in remaining }
                }
            return QueueTransition(
                q.copy(
                    shuffleEnabled = enabled,
                    traversal = order.copy(upcoming = order.priority + sorted),
                )
            )
        }
        return when (command) {
            is QueueCommand.SetShuffle -> shuffle(command.enabled)
            QueueCommand.ToggleShuffle -> shuffle(!q.shuffleEnabled)
            is QueueCommand.Replace -> {
                require(
                    command.entries.isNotEmpty() &&
                        command.entries.distinctBy { it.id }.size == command.entries.size
                )
                val index = command.entries.indexOfFirst { it.id == command.currentEntryId }
                require(index >= 0)
                val remaining = command.entries.drop(index + 1).map { it.id }
                QueueTransition(
                    QueueSnapshot(
                        command.entries.toList(),
                        command.currentEntryId,
                        0,
                        true,
                        q.shuffleEnabled,
                        QueueTraversal(
                            upcoming =
                                if (q.shuffleEnabled) remaining.shuffled(random) else remaining
                        ),
                    )
                )
            }
            is QueueCommand.Add -> add(q, command)
            is QueueCommand.MoveUpcoming -> {
                val entries = order.upcoming.toMutableList()
                val index = entries.indexOf(command.id)
                require(index >= 0 && command.toIndex in entries.indices)
                entries.add(command.toIndex, entries.removeAt(index))
                val original =
                    if (q.shuffleEnabled) q.entries
                    else {
                        val ids = order.upcoming.toSet()
                        val records = q.entries.associateBy { it.id }
                        val replacements = entries.iterator()
                        q.entries.map {
                            if (it.id in ids) records.getValue(replacements.next()) else it
                        }
                    }
                QueueTransition(
                    q.copy(
                        entries = original,
                        traversal = order.copy(upcoming = entries.toList(), priority = emptyList()),
                    )
                )
            }
            is QueueCommand.Remove -> {
                if (q.entries.none { it.id == command.id }) QueueTransition(snapshot)
                else {
                    val future = order.upcoming.filterNot { it == command.id }
                    val removedCurrent = q.currentEntryId == command.id
                    val current = if (removedCurrent) future.firstOrNull() else q.currentEntryId
                    val upcoming = future.filterNot { it == current }
                    QueueTransition(
                        q.copy(
                            entries = q.entries.filterNot { it.id == command.id },
                            currentEntryId = current,
                            positionMs = if (removedCurrent) 0 else q.positionMs,
                            playWhenReady =
                                if (removedCurrent) current != null && q.playWhenReady
                                else q.playWhenReady,
                            traversal =
                                QueueTraversal(
                                    order.history.filterNot { it == command.id },
                                    upcoming,
                                    order.priority.filter { it in upcoming },
                                ),
                        ),
                        seekToMs = if (removedCurrent && current != null) 0L else null,
                        stopPlayback = removedCurrent && current == null,
                    )
                }
            }
            is QueueCommand.Select ->
                if (q.entries.any { it.id == command.id }) {
                    if (command.id == q.currentEntryId)
                        QueueTransition(q.copy(positionMs = 0), seekToMs = 0)
                    else select(command.id)
                } else QueueTransition(snapshot)
            QueueCommand.Next -> next()
            QueueCommand.Previous -> {
                if (q.currentEntryId == null) QueueTransition(snapshot)
                else if (q.positionMs > 3000 || order.history.isEmpty())
                    QueueTransition(q.copy(positionMs = 0), seekToMs = 0)
                else {
                    val previous = order.history.last()
                    val upcoming =
                        listOf(q.currentEntryId) +
                            order.upcoming.filterNot { it == previous || it == q.currentEntryId }
                    val priority =
                        listOf(q.currentEntryId) +
                            order.priority.filterNot { it == previous || it == q.currentEntryId }
                    QueueTransition(
                        q.copy(
                            currentEntryId = previous,
                            positionMs = 0,
                            traversal =
                                QueueTraversal(order.history.dropLast(1), upcoming, priority),
                        ),
                        seekToMs = 0,
                    )
                }
            }
            is QueueCommand.Ended ->
                if (command.entryId == q.currentEntryId) next() else QueueTransition(snapshot)
        }
    }

    private fun add(q: QueueSnapshot, command: QueueCommand.Add): QueueTransition {
        if (command.entries.isEmpty()) return QueueTransition(q)
        val all = q.entries + command.entries
        require(all.map { it.id }.distinct().size == all.size) { "Queue entry IDs must be unique" }
        val order = q.traversal!!
        val added = command.entries.map { it.id }
        val entries =
            if (command.position == QueueInsertion.END) all
            else {
                val ordinary = order.upcoming.drop(order.priority.size).toSet()
                val index =
                    q.entries.indexOfFirst { it.id in ordinary }.takeIf { it >= 0 }
                        ?: q.entries.size
                q.entries.take(index) + command.entries + q.entries.drop(index)
            }
        val current = q.currentEntryId ?: added.first()
        val pending =
            if (command.position == QueueInsertion.NEXT) added + order.upcoming
            else order.upcoming + added
        val upcoming = pending.filterNot { it == current }
        val priority =
            if (command.position == QueueInsertion.NEXT)
                (added + order.priority).filterNot { it == current }
            else order.priority
        return QueueTransition(
            q.copy(
                entries = entries,
                currentEntryId = current,
                positionMs = if (q.currentEntryId == null) 0 else q.positionMs,
                playWhenReady = q.currentEntryId != null && q.playWhenReady,
                traversal = order.copy(upcoming = upcoming, priority = priority),
            )
        )
    }
}
