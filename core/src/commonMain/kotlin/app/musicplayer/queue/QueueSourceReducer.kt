package app.musicplayer.queue

import app.musicplayer.model.*
import kotlin.random.Random

internal object QueueSourceReducer {
    fun reduce(q: QueueSnapshot, command: QueueCommand, random: Random): QueueTransition? {
        if (command is QueueCommand.Replace) {
            val source = command.source ?: return null
            val result = QueueReducer.reduceKnown(q.copy(shuffleEnabled = false), command, random)
            return result.copy(
                snapshot =
                    result.snapshot.copy(
                        shuffleEnabled = false,
                        source =
                            source.copy(
                                shuffleRequested =
                                    source.shuffleRequested ||
                                        q.shuffleEnabled ||
                                        command.shuffleEnabled == true
                            ),
                    )
            )
        }
        val source = q.source ?: return null
        if (command is QueueCommand.SourceReady) {
            if (
                source.sessionId != command.sessionId ||
                    source.phase in setOf(QueueSourcePhase.CHANGED, QueueSourcePhase.DETACHED)
            )
                return QueueTransition(q)
            return QueueTransition(
                q.copy(
                    source =
                        source.copy(
                            error = null,
                            phase =
                                if (source.phase == QueueSourcePhase.FAILED)
                                    QueueSourcePhase.LOADING
                                else source.phase,
                        )
                )
            )
        }
        if (command is QueueCommand.SourceFailed) {
            if (source.sessionId != command.sessionId || source.phase == QueueSourcePhase.DETACHED)
                return QueueTransition(q)
            return QueueTransition(
                q.copy(
                    source =
                        source.copy(
                            phase =
                                if (command.error.code == ErrorCode.DATABASE) source.phase
                                else if (command.changed) QueueSourcePhase.CHANGED
                                else QueueSourcePhase.FAILED,
                            error = command.error,
                        )
                )
            )
        }
        if (command is QueueCommand.AppendSourcePage) return append(q, command, random)
        if (!source.hasMore) {
            if (source.waiting && command == QueueCommand.Next) return advance(q, random)
            if (
                source.waiting &&
                    (command == QueueCommand.Previous || command is QueueCommand.Select)
            )
                return QueueReducer.reduceKnown(
                    q.copy(source = source.copy(waiting = false)),
                    command,
                    random,
                )
            return null
        }
        return when (command) {
            is QueueCommand.SetShuffle,
            QueueCommand.ToggleShuffle -> {
                val wanted =
                    if (command is QueueCommand.SetShuffle) command.enabled
                    else !source.shuffleRequested
                QueueTransition(q.copy(source = source.copy(shuffleRequested = wanted)))
            }
            QueueCommand.Next -> advance(q, random)
            is QueueCommand.Ended ->
                if (command.entryId == q.currentEntryId) advance(q, random) else QueueTransition(q)
            is QueueCommand.Select -> {
                if (q.entries.none { it.id == command.id }) return QueueTransition(q)
                val detached = command.id in source.deferredEndIds
                val updated =
                    q.copy(
                        source =
                            source.copy(
                                waiting = false,
                                phase = if (detached) QueueSourcePhase.DETACHED else source.phase,
                                deferredEndIds =
                                    if (detached) emptyList() else source.deferredEndIds,
                            )
                    )
                val result = QueueReducer.reduceKnown(updated, command, random)
                if (detached) finish(result, random) else result
            }
            QueueCommand.Previous ->
                QueueReducer.reduceKnown(
                    q.copy(source = source.copy(waiting = false)),
                    command,
                    random,
                )
            is QueueCommand.Add -> add(q, command, random)
            is QueueCommand.Remove -> remove(q, command, random)
            is QueueCommand.MoveUpcoming -> {
                val future = q.traversal!!.upcoming
                val target = future.getOrNull(command.toIndex)
                if (
                    target == null ||
                        command.id !in future ||
                        (target in source.deferredEndIds) != (command.id in source.deferredEndIds)
                )
                    QueueTransition(q)
                else QueueReducer.reduceKnown(q, command, random)
            }
            else -> null
        }
    }

    private fun append(
        q: QueueSnapshot,
        command: QueueCommand.AppendSourcePage,
        random: Random,
    ): QueueTransition {
        val source = q.source!!
        if (source.sessionId != command.sessionId || !source.hasMore) return QueueTransition(q)
        val identities = command.rows.map { it.identity }
        val end = command.fromOrdinal.toLong() + identities.size
        if (
            command.fromOrdinal < 0 ||
                end > Int.MAX_VALUE ||
                command.fromOrdinal > source.acceptedRows.size ||
                identities.withIndex().any { it.value.ordinal != command.fromOrdinal + it.index }
        )
            return changed(q)
        val overlap = minOf(identities.size, source.acceptedRows.size - command.fromOrdinal)
        if (
            identities.take(overlap) !=
                source.acceptedRows.subList(command.fromOrdinal, command.fromOrdinal + overlap)
        )
            return changed(q)
        val newRows = command.rows.drop(overlap)
        if (newRows.isEmpty() && !command.complete) return QueueTransition(q)
        val added = newRows.mapNotNull { it.entry }
        if (
            added.map { it.id }.distinct().size != added.size ||
                added.any { row -> q.entries.any { it.id == row.id } }
        )
            return changed(q)
        val ends = source.deferredEndIds.toSet()
        val originalIndex =
            q.entries.indexOfFirst { it.id in ends }.takeIf { it >= 0 } ?: q.entries.size
        val future = q.traversal!!.upcoming
        val nextIndex = future.indexOfFirst { it in ends }.takeIf { it >= 0 } ?: future.size
        val updated =
            q.copy(
                entries = q.entries.take(originalIndex) + added + q.entries.drop(originalIndex),
                traversal =
                    q.traversal.copy(
                        upcoming =
                            future.take(nextIndex) + added.map { it.id } + future.drop(nextIndex)
                    ),
                source =
                    source.copy(
                        acceptedRows = source.acceptedRows + newRows.map { it.identity },
                        phase =
                            if (command.complete) QueueSourcePhase.COMPLETE
                            else QueueSourcePhase.LOADING,
                        error = null,
                        deferredEndIds =
                            if (command.complete) emptyList() else source.deferredEndIds,
                    ),
            )
        val result =
            if (command.complete) finish(QueueTransition(updated), random)
            else QueueTransition(updated)
        return resumeWaiting(result, random)
    }

    private fun finish(result: QueueTransition, random: Random): QueueTransition {
        val q = result.snapshot
        val source = q.source!!
        val shuffled =
            QueueReducer.reduceKnown(q, QueueCommand.SetShuffle(source.shuffleRequested), random)
                .snapshot
        return result.copy(snapshot = shuffled)
    }

    private fun advance(q: QueueSnapshot, random: Random): QueueTransition {
        val next = QueueOrder.next(q)
        if (next == null && q.source!!.hasMore)
            return QueueTransition(
                q.copy(source = q.source.copy(waiting = true)),
                waitForSource = true,
            )
        if (next == null)
            return QueueTransition(
                q.copy(playWhenReady = false, source = q.source!!.copy(waiting = false)),
                stopPlayback = true,
            )
        return QueueReducer.reduceKnown(
            q.copy(source = q.source!!.copy(waiting = false)),
            QueueCommand.Select(next.id),
            random,
        )
    }

    private fun resumeWaiting(result: QueueTransition, random: Random): QueueTransition {
        val q = result.snapshot
        if (q.source!!.waiting && q.playWhenReady) return advance(q, random)
        return result
    }

    private fun add(q: QueueSnapshot, command: QueueCommand.Add, random: Random): QueueTransition {
        if (command.entries.isEmpty()) return QueueTransition(q)
        val source = q.source!!
        var result = QueueReducer.reduceKnown(q, command, random)
        var updated =
            result.snapshot.copy(
                source =
                    source.copy(
                        deferredEndIds =
                            if (command.position == QueueInsertion.END)
                                source.deferredEndIds + command.entries.map { it.id }
                            else source.deferredEndIds
                    )
            )
        if (q.currentEntryId == null) {
            val ids = command.entries.map { it.id }
            val upcoming =
                if (command.position == QueueInsertion.NEXT) ids + q.traversal!!.upcoming
                else q.traversal!!.upcoming + ids
            updated =
                updated.copy(
                    currentEntryId = null,
                    playWhenReady = q.playWhenReady,
                    traversal =
                        q.traversal.copy(
                            upcoming = upcoming,
                            priority =
                                if (command.position == QueueInsertion.NEXT)
                                    ids + q.traversal.priority
                                else q.traversal.priority,
                        ),
                )
        }
        result = result.copy(snapshot = updated)
        return resumeWaiting(result, random)
    }

    private fun remove(
        q: QueueSnapshot,
        command: QueueCommand.Remove,
        random: Random,
    ): QueueTransition {
        val source = q.source!!
        if (command.id == q.currentEntryId && QueueOrder.next(q) == null) {
            val future = q.traversal!!.upcoming.filterNot { it == command.id }
            return QueueTransition(
                q.copy(
                    entries = q.entries.filterNot { it.id == command.id },
                    currentEntryId = null,
                    positionMs = 0,
                    source =
                        source.copy(
                            waiting = true,
                            deferredEndIds = source.deferredEndIds.filterNot { it == command.id },
                        ),
                    traversal =
                        QueueTraversal(
                            q.traversal.history.filterNot { it == command.id },
                            future,
                            q.traversal.priority.filter { it in future },
                        ),
                ),
                waitForSource = true,
            )
        }
        val result = QueueReducer.reduceKnown(q, command, random)
        return result.copy(
            snapshot =
                result.snapshot.copy(
                    source =
                        source.copy(
                            deferredEndIds = source.deferredEndIds.filterNot { it == command.id }
                        )
                )
        )
    }

    private fun changed(q: QueueSnapshot) =
        QueueTransition(
            q.copy(
                source =
                    q.source!!.copy(
                        phase = QueueSourcePhase.CHANGED,
                        error = AppError(ErrorCode.PROVIDER_CHANGED, "queue.source.page", false),
                    )
            )
        )
}
