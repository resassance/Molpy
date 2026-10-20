package app.musicplayer.queue

import app.musicplayer.model.*

sealed interface QueueCommand {
    data class Add(val entries: List<QueueEntry>) : QueueCommand
    data class Remove(val id: QueueEntryId) : QueueCommand
    data class Select(val id: QueueEntryId) : QueueCommand
    data object Next : QueueCommand
    data object Previous : QueueCommand
    data class Ended(val entryId: QueueEntryId) : QueueCommand
}

data class QueueTransition(val snapshot: QueueSnapshot, val seekToMs: Long? = null, val stopPlayback: Boolean = false)

/** Pure occurrence-based rules. Tracks may repeat; entry IDs may not. */
object QueueReducer {
    fun reduce(snapshot: QueueSnapshot, command: QueueCommand): QueueTransition {
        val currentIndex = snapshot.entries.indexOfFirst { it.id == snapshot.currentEntryId }
        fun select(index: Int) = QueueTransition(snapshot.copy(
            currentEntryId = snapshot.entries[index].id, positionMs = 0,
        ), seekToMs = 0)
        fun next(): QueueTransition = when {
            currentIndex < 0 -> QueueTransition(snapshot)
            currentIndex < snapshot.entries.lastIndex -> select(currentIndex + 1)
            else -> QueueTransition(snapshot.copy(playWhenReady = false), stopPlayback = true)
        }
        return when (command) {
            is QueueCommand.Add -> {
                val entries = snapshot.entries + command.entries
                require(entries.map { it.id }.distinct().size == entries.size) { "Queue entry IDs must be unique" }
                QueueTransition(snapshot.copy(entries = entries.toList()))
            }
            is QueueCommand.Remove -> {
                val removedIndex = snapshot.entries.indexOfFirst { it.id == command.id }
                if (removedIndex < 0) QueueTransition(snapshot)
                else {
                    val entries = snapshot.entries.filterNot { it.id == command.id }
                    if (command.id != snapshot.currentEntryId) QueueTransition(snapshot.copy(entries = entries))
                    else {
                        val following = snapshot.entries.getOrNull(removedIndex + 1)
                        QueueTransition(snapshot.copy(entries = entries, currentEntryId = following?.id, positionMs = 0,
                            playWhenReady = following != null && snapshot.playWhenReady),
                            seekToMs = following?.let { 0L }, stopPlayback = following == null)
                    }
                }
            }
            is QueueCommand.Select -> {
                val index = snapshot.entries.indexOfFirst { it.id == command.id }
                if (index < 0) QueueTransition(snapshot) else select(index)
            }
            QueueCommand.Next -> next()
            QueueCommand.Previous -> if (currentIndex < 0) QueueTransition(snapshot)
                else select(if (snapshot.positionMs > 3000) currentIndex else (currentIndex - 1).coerceAtLeast(0))
            is QueueCommand.Ended -> if (command.entryId == snapshot.currentEntryId) next() else QueueTransition(snapshot)
        }
    }
}
