package app.musicplayer.queue

import app.musicplayer.catalog.*
import app.musicplayer.model.*
import kotlin.random.Random
import kotlin.test.*

class QueueSourceReducerTest {
    private fun entry(id: String, track: String = id) = QueueEntry(QueueEntryId(id), TrackId(track))

    private fun identity(n: Int, id: String = "song-$n") =
        PlaylistRowIdentity(n, "row-$n", SourceRef("youtube", "video", id))

    private fun start(): QueueSnapshot =
        QueueReducer.reduce(
                QueueSnapshot(),
                QueueCommand.Replace(
                    listOf(entry("a")),
                    QueueEntryId("a"),
                    QueueSourceState(
                        "session",
                        PublicPlaylistRef("Mix", SourceRef("youtube", "playlist", "PLtest")),
                        0,
                        listOf(identity(0)),
                    ),
                ),
            )
            .snapshot

    private fun reduce(q: QueueSnapshot, command: QueueCommand) =
        QueueReducer.reduce(q, command, Random(42)).snapshot

    private fun ids(q: QueueSnapshot) = QueueOrder.upcoming(q).map { it.id.value }

    private fun page(from: Int = 1, complete: Boolean = false) =
        QueueCommand.AppendSourcePage(
            "session",
            from,
            listOf(
                PlaylistQueueRow(identity(from), entry("b")),
                PlaylistQueueRow(identity(from + 1), entry("c")),
            ),
            complete,
        )

    @Test
    fun nextAndEndStraddleUnknownTail() {
        var q = start()
        q = reduce(q, QueueCommand.Add(listOf(entry("tail")), QueueInsertion.END))
        q = reduce(q, QueueCommand.Add(listOf(entry("n1")), QueueInsertion.NEXT))
        q = reduce(q, QueueCommand.Add(listOf(entry("n2")), QueueInsertion.NEXT))
        q = reduce(q, page())
        assertEquals(listOf("n2", "n1", "b", "c", "tail"), ids(q))
        repeat(4) { q = reduce(q, QueueCommand.Next) }
        assertEquals("c", q.currentEntryId!!.value)
        assertNull(QueueOrder.next(q))
        q = reduce(q, QueueCommand.Next)
        assertTrue(q.source!!.waiting)
        assertTrue(q.playWhenReady)
        assertEquals("c", q.currentEntryId!!.value)
    }

    @Test
    fun samePageIsIdempotentButRepeatedTrackIsNotDeduplicated() {
        val command =
            QueueCommand.AppendSourcePage(
                "session",
                1,
                listOf(
                    PlaylistQueueRow(identity(1, "same"), entry("one", "same")),
                    PlaylistQueueRow(identity(2, "same"), entry("two", "same")),
                ),
                false,
            )
        val once = reduce(start(), command)
        assertEquals(listOf("one", "two"), ids(once))
        assertEquals(once, reduce(once, command))
        val removed = reduce(once, QueueCommand.Remove(QueueEntryId("one")))
        assertEquals(removed, reduce(removed, command))
    }

    @Test
    fun pauseAtGapPreventsAutomaticPlayback() {
        var q = reduce(start(), QueueCommand.Next).copy(playWhenReady = false)
        q = reduce(q, page())
        assertEquals("a", q.currentEntryId!!.value)
        assertTrue(q.source!!.waiting)
        assertFalse(q.playWhenReady)
        assertEquals(listOf("b", "c"), ids(q))
    }

    @Test
    fun removeCurrentWaitsAtGap() {
        var q = reduce(start(), QueueCommand.Add(listOf(entry("tail")), QueueInsertion.END))
        q = reduce(q, QueueCommand.Remove(QueueEntryId("a")))
        assertNull(q.currentEntryId)
        assertTrue(q.source!!.waiting)
        assertTrue(q.playWhenReady)
        q = reduce(q, page())
        assertEquals("b", q.currentEntryId!!.value)
        assertEquals(listOf("c", "tail"), ids(q))
    }

    @Test
    fun repeatedNextDoesNotSkipFirstArrivingSong() {
        var q = start()
        repeat(4) { q = reduce(q, QueueCommand.Next) }
        q = reduce(q, page())
        assertEquals("b", q.currentEntryId!!.value)
        assertEquals(listOf("c"), ids(q))
        assertFalse(q.source!!.waiting)
    }

    @Test
    fun pendingShuffleAppliesOnceOnComplete() {
        var q = reduce(start(), QueueCommand.ToggleShuffle)
        assertFalse(q.shuffleEnabled)
        assertTrue(q.source!!.shuffleRequested)
        q = reduce(q, QueueCommand.Add(listOf(entry("next")), QueueInsertion.NEXT))
        q = reduce(q, page(complete = true))
        assertTrue(q.shuffleEnabled)
        assertEquals("next", ids(q).first())
        assertEquals(setOf("next", "b", "c"), ids(q).toSet())
        assertEquals(q, reduce(q, page(complete = true)))
        assertEquals("a", q.currentEntryId!!.value)
    }

    @Test
    fun selectManualTailDetachesSource() {
        var q = reduce(start(), QueueCommand.Add(listOf(entry("tail")), QueueInsertion.END))
        q = reduce(q, QueueCommand.Select(QueueEntryId("tail")))
        assertEquals(QueueSourcePhase.DETACHED, q.source!!.phase)
        assertEquals("tail", q.currentEntryId!!.value)
        assertEquals(q, reduce(q, page()))
    }

    @Test
    fun movingAcrossGapIsRejectedAndRemovingTailCleansMarker() {
        var q = reduce(start(), page())
        q = reduce(q, QueueCommand.Add(listOf(entry("tail")), QueueInsertion.END))
        assertEquals(q, reduce(q, QueueCommand.MoveUpcoming(QueueEntryId("tail"), 0)))
        q = reduce(q, QueueCommand.Remove(QueueEntryId("tail")))
        assertTrue(q.source!!.deferredEndIds.isEmpty())
    }

    @Test
    fun previousCancelsOldWaitingBeforeLatePage() {
        var q = reduce(start(), page())
        q = reduce(q, QueueCommand.Next)
        q = reduce(q, QueueCommand.Next)
        q = reduce(q, QueueCommand.Next)
        assertTrue(q.source!!.waiting)
        q = reduce(q, QueueCommand.Previous)
        assertFalse(q.source!!.waiting)
        q =
            reduce(
                q,
                QueueCommand.AppendSourcePage(
                    "session",
                    3,
                    listOf(PlaylistQueueRow(identity(3), entry("d"))),
                    false,
                ),
            )
        assertEquals("b", q.currentEntryId!!.value)
        assertEquals(listOf("c", "d"), ids(q))
    }

    @Test
    fun appendAfterReplacementCannotMutateNewQueue() {
        val replaced =
            reduce(start(), QueueCommand.Replace(listOf(entry("fresh")), QueueEntryId("fresh")))
        assertNull(replaced.source)
        assertEquals(replaced, reduce(replaced, page()))
    }

    @Test
    fun emptyFinalPageStopsAnEmptyRemainingQueue() {
        val waiting = reduce(start(), QueueCommand.Remove(QueueEntryId("a")))
        val done = reduce(waiting, QueueCommand.AppendSourcePage("session", 1, emptyList(), true))
        assertFalse(done.playWhenReady)
        assertFalse(done.source!!.waiting)
        assertNull(done.currentEntryId)
    }
}
