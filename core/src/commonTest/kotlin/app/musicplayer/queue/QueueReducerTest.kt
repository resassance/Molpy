package app.musicplayer.queue

import app.musicplayer.model.*
import kotlin.test.*

class QueueReducerTest {
    private val a = QueueEntry(QueueEntryId("a"), TrackId("same-track"))
    private val b = QueueEntry(QueueEntryId("b"), a.trackId)
    private val c = QueueEntry(QueueEntryId("c"), TrackId("other"))

    private fun queue(current: QueueEntry = b, position: Long = 0, playing: Boolean = true) =
        QueueSnapshot(
            listOf(a, b, c),
            current.id,
            position,
            playing,
            traversal =
                QueueTraversal(
                    history = listOf(a, b, c).takeWhile { it.id != current.id }.map { it.id },
                    upcoming =
                        listOf(a, b, c).dropWhile { it.id != current.id }.drop(1).map { it.id },
                ),
        )

    @Test
    fun addPreservesDuplicateTrackOccurrencesAndExistingIdentities() {
        val added = QueueReducer.reduce(QueueSnapshot(), QueueCommand.Add(listOf(a, b))).snapshot
        assertEquals(listOf(a, b), added.entries)
        assertNotEquals(added.entries[0].id, added.entries[1].id)
        assertEquals(a.id, added.currentEntryId)
    }

    @Test
    fun previousUsesStrictThreeSecondBoundary() {
        listOf(2999L to a, 3000L to a, 3001L to b).forEach { (position, expected) ->
            val result = QueueReducer.reduce(queue(position = position), QueueCommand.Previous)
            assertEquals(expected.id, result.snapshot.currentEntryId, "position=$position")
            assertEquals(0L, result.snapshot.positionMs)
            assertEquals(0L, result.seekToMs)
            assertTrue(result.snapshot.playWhenReady)
        }
    }

    @Test
    fun firstPreviousRestartsAndLastNextStops() {
        assertEquals(
            a.id,
            QueueReducer.reduce(queue(a, 2000), QueueCommand.Previous).snapshot.currentEntryId,
        )
        val afterLast = QueueReducer.reduce(queue(c, 8000), QueueCommand.Next)
        assertTrue(afterLast.stopPlayback)
        assertFalse(afterLast.snapshot.playWhenReady)
        assertEquals(c.id, afterLast.snapshot.currentEntryId)
    }

    @Test
    fun removeCurrentUsesFollowingEntryAndPreservesIntent() {
        listOf(false, true).forEach { intent ->
            val transition = QueueReducer.reduce(queue(playing = intent), QueueCommand.Remove(b.id))
            assertEquals(listOf(a, c), transition.snapshot.entries)
            assertEquals(c.id, transition.snapshot.currentEntryId)
            assertEquals(0L, transition.snapshot.positionMs)
            assertEquals(intent, transition.snapshot.playWhenReady)
            assertFalse(transition.stopPlayback)
        }
        val last = QueueReducer.reduce(queue(c), QueueCommand.Remove(c.id))
        assertNull(last.snapshot.currentEntryId)
        assertTrue(last.stopPlayback)
        assertFalse(last.snapshot.playWhenReady)
    }

    @Test
    fun removingOtherEntryDoesNotChangeCurrentOrPosition() {
        val result = QueueReducer.reduce(queue(position = 42), QueueCommand.Remove(a.id)).snapshot
        assertEquals(listOf(b, c), result.entries)
        assertEquals(b.id, result.currentEntryId)
        assertEquals(42L, result.positionMs)
    }

    @Test
    fun emptyCommandsStayStopped() {
        listOf(
                QueueCommand.Next,
                QueueCommand.Previous,
                QueueCommand.Ended(a.id),
                QueueCommand.Remove(a.id),
                QueueCommand.Select(a.id),
            )
            .forEach {
                val result = QueueReducer.reduce(QueueSnapshot(), it)
                assertEquals(QueueSnapshot(), result.snapshot)
                assertFalse(result.snapshot.playWhenReady)
            }
    }

    @Test
    fun selectResetsPositionAndUnknownSelectionDoesNothing() {
        assertEquals(
            a.id,
            QueueReducer.reduce(queue(position = 123), QueueCommand.Select(a.id))
                .snapshot
                .currentEntryId,
        )
        assertEquals(
            queue(),
            QueueReducer.reduce(queue(), QueueCommand.Select(QueueEntryId("missing"))).snapshot,
        )
    }

    @Test
    fun endedAdvancesOnlyTheCurrentOccurrence() {
        val result = QueueReducer.reduce(queue(a), QueueCommand.Ended(a.id))
        assertEquals(b.id, result.snapshot.currentEntryId)
        assertEquals(0L, result.snapshot.positionMs)
        assertEquals(queue(), QueueReducer.reduce(queue(), QueueCommand.Ended(a.id)).snapshot)
        assertTrue(QueueReducer.reduce(queue(c), QueueCommand.Ended(c.id)).stopPlayback)
    }
}
