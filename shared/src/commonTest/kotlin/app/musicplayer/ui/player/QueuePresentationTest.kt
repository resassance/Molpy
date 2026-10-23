package app.musicplayer.ui.player

import app.musicplayer.catalog.PublicPlaylistRef
import app.musicplayer.model.*
import app.musicplayer.ui.sampleTrack
import kotlin.test.*

class QueuePresentationTest {
    @Test
    fun loadingStorageAcknowledgementFailureAllowsExplicitRetry() {
        val queue = unfinishedQueue()
        val error = AppError(ErrorCode.DATABASE, "queue.source.save", false)
        val shown =
            presentQueue(queue.copy(source = queue.source!!.copy(error = error)), emptyMap())
        assertEquals(QueueSourcePhase.LOADING, shown.gap?.phase)
        assertTrue(shown.gap!!.canRetry)
        assertEquals(error, shown.sourceError)
        assertTrue(shown.sourceCanRetry)
    }

    @Test
    fun completedStorageAcknowledgementFailureHasRetryWithoutUnknownGap() {
        val queue = unfinishedQueue()
        val error = AppError(ErrorCode.DATABASE, "queue.source.save", false)
        val shown =
            presentQueue(
                queue.copy(
                    source = queue.source!!.copy(phase = QueueSourcePhase.COMPLETE, error = error)
                ),
                emptyMap(),
            )
        assertNull(shown.gap)
        assertEquals(error, shown.sourceError)
        assertTrue(shown.sourceCanRetry)
    }

    @Test
    fun manualRateLimitRetryDoesNotEnableRetryForChangedOrDetachedSource() {
        val queue = unfinishedQueue()
        val error = AppError(ErrorCode.RATE_LIMIT, "queue.source.page", false)
        val failed = queue.source!!.copy(phase = QueueSourcePhase.FAILED, error = error)
        val shown = presentQueue(queue.copy(source = failed), emptyMap())
        assertTrue(shown.gap!!.canRetry)
        assertTrue(shown.sourceCanRetry)
        assertFalse(
            presentQueue(
                    queue.copy(source = failed.copy(phase = QueueSourcePhase.CHANGED)),
                    emptyMap(),
                )
                .sourceCanRetry
        )
        assertFalse(
            presentQueue(
                    queue.copy(source = failed.copy(phase = QueueSourcePhase.DETACHED)),
                    emptyMap(),
                )
                .sourceCanRetry
        )
    }

    @Test
    fun gapStatusIsNotPlayableRowAndPrecedesDeferredTail() {
        val queue = unfinishedQueue()
        val shown = presentQueue(queue, emptyMap())
        assertEquals(listOf("loaded", "manual"), shown.upcoming.map { it.entryId.value })
        assertEquals(1, shown.gap?.beforeIndex)
        assertEquals(QueueSourcePhase.LOADING, shown.gap?.phase)
        assertTrue(shown.canRequestNext)
        assertFalse(shown.gap!!.canRetry)
        val boundary =
            queue.copy(traversal = QueueTraversal(upcoming = listOf(QueueEntryId("manual"))))
        val waiting =
            presentQueue(boundary.copy(source = boundary.source!!.copy(waiting = true)), emptyMap())
        assertNull(waiting.next)
        assertTrue(waiting.canRequestNext)
        assertEquals(0, waiting.gap?.beforeIndex)
        assertTrue(waiting.gap!!.waiting)
    }

    @Test
    fun pendingShuffleIsDistinctFromActiveShuffleAndSurvivesFailure() {
        val queue = unfinishedQueue()
        val failure = AppError(ErrorCode.NETWORK, "queue.page", true)
        val shown =
            presentQueue(
                queue.copy(
                    source =
                        queue.source!!.copy(
                            shuffleRequested = true,
                            phase = QueueSourcePhase.FAILED,
                            error = failure,
                        )
                ),
                emptyMap(),
            )
        assertTrue(shown.shufflePending)
        assertFalse(queue.shuffleEnabled)
        assertEquals(failure, shown.gap?.error)
        assertTrue(shown.gap!!.canRetry)
        val complete =
            presentQueue(
                queue.copy(
                    shuffleEnabled = true,
                    source =
                        queue.source!!.copy(
                            phase = QueueSourcePhase.COMPLETE,
                            shuffleRequested = true,
                        ),
                ),
                emptyMap(),
            )
        assertFalse(complete.shufflePending)
        assertNull(complete.gap)
    }

    @Test
    fun changedSourceOffersReopenAndRecoveryErrorRemainsVisible() {
        val queue = unfinishedQueue()
        val changed =
            presentQueue(
                queue.copy(source = queue.source!!.copy(phase = QueueSourcePhase.CHANGED)),
                emptyMap(),
            )
        assertEquals(queue.source!!.ref, changed.gap?.reopen)
        assertFalse(changed.gap!!.canRetry)
        val error = AppError(ErrorCode.DATABASE, "queue.source.restore", true)
        assertEquals(
            error,
            presentQueue(queue.copy(source = null, sourceRecoveryError = error), emptyMap())
                .sourceRecoveryError,
        )
    }

    @Test
    fun gapRemainsVisibleWithoutDeferredRowsButDisappearsWhenDetached() {
        val queue = unfinishedQueue()
        val shown =
            presentQueue(
                queue.copy(source = queue.source!!.copy(deferredEndIds = emptyList())),
                emptyMap(),
            )
        assertEquals(2, shown.gap?.beforeIndex)
        assertNull(
            presentQueue(
                    queue.copy(source = queue.source!!.copy(phase = QueueSourcePhase.DETACHED)),
                    emptyMap(),
                )
                .gap
        )
    }

    @Test
    fun movesAcrossGapAreRejectedWhileMovesWithinEachSideAreAllowed() {
        val queue = unfinishedQueue()
        assertFalse(canMoveQueueEntry(queue, QueueEntryId("loaded"), 1))
        assertFalse(canMoveQueueEntry(queue, QueueEntryId("manual"), 0))
        assertTrue(canMoveQueueEntry(queue, QueueEntryId("loaded"), 0))
        assertFalse(canMoveQueueEntry(queue, QueueEntryId("loaded"), -1))
        assertTrue(canMoveQueueEntry(queue.copy(source = null), QueueEntryId("loaded"), 1))
    }

    private fun unfinishedQueue(): QueueSnapshot {
        val entries =
            listOf("current", "loaded", "manual").map { QueueEntry(QueueEntryId(it), TrackId(it)) }
        return QueueSnapshot(
            entries,
            entries[0].id,
            source =
                QueueSourceState(
                    "session",
                    PublicPlaylistRef("List", SourceRef("ytm", "playlist", "list")),
                    0,
                    emptyList(),
                    deferredEndIds = listOf(entries[2].id),
                ),
        )
    }

    @Test
    fun neighborsUseOccurrencesAndDoNotWrapAtEdges() {
        val a = sampleTrack("A")
        val b = sampleTrack("B")
        val entries =
            listOf(
                QueueEntry(QueueEntryId("a1"), a.id),
                QueueEntry(QueueEntryId("a2"), a.id),
                QueueEntry(QueueEntryId("b"), b.id),
            )
        val tracks = mapOf(a.id to a, b.id to b)
        val first = presentQueue(QueueSnapshot(entries, entries[0].id), tracks)
        assertNull(first.previous)
        assertEquals(entries[1].id, first.next?.entryId)
        val middle =
            presentQueue(
                QueueSnapshot(
                    entries,
                    entries[1].id,
                    traversal =
                        QueueTraversal(
                            history = listOf(entries[0].id),
                            upcoming = listOf(entries[2].id),
                        ),
                ),
                tracks,
            )
        assertEquals(entries[0].id, middle.previous?.entryId)
        assertEquals(entries[2].id, middle.next?.entryId)
        assertNull(presentQueue(QueueSnapshot(entries, entries[2].id), tracks).next)
    }
}
