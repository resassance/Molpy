package app.musicplayer.queue

import app.musicplayer.model.*
import kotlin.random.Random
import kotlin.test.*

class QueueShuffleTest {
    private fun entry(id: String, track: String = id) = QueueEntry(QueueEntryId(id), TrackId(track))

    private val a = entry("a")
    private val b = entry("b")
    private val c = entry("c")
    private val d = entry("d")
    private val e = entry("e")
    private val x = entry("x")
    private val y = entry("y")
    private val z = entry("z")

    private fun start(vararg entries: QueueEntry, at: QueueEntry = entries.first()) =
        QueueReducer.reduce(QueueSnapshot(), QueueCommand.Replace(entries.toList(), at.id)).snapshot

    private fun step(q: QueueSnapshot, command: QueueCommand) =
        QueueReducer.reduce(q, command, Random(42)).snapshot

    private fun upcoming(q: QueueSnapshot) = QueueOrder.upcoming(q).map { it.id }

    @Test
    fun explicitShuffledReplacementKeepsThePrefixAndEveryOccurrence() {
        val duplicate = entry("other-a", "a")
        val entries = listOf(a, b, duplicate, c)
        val result =
            QueueReducer.reduce(
                    QueueSnapshot(),
                    QueueCommand.Replace(entries, duplicate.id, shuffleEnabled = true),
                    Random(42),
                )
                .snapshot
        assertEquals(entries, result.entries)
        assertEquals(duplicate.id, result.currentEntryId)
        assertTrue(result.shuffleEnabled)
        assertEquals(setOf(a.id, b.id, c.id), upcoming(result).toSet())
        assertEquals(3, upcoming(result).size)
        assertTrue(result.traversal!!.history.isEmpty())
        assertTrue(result.traversal!!.priority.isEmpty())
    }

    @Test
    fun togglePreservesCurrentPlaybackAndCanonicalOrder() {
        val before = start(a, b, c, d, e, at = b).copy(positionMs = 4321, playWhenReady = false)
        val after = step(before, QueueCommand.ToggleShuffle)
        assertTrue(after.shuffleEnabled)
        assertEquals(before.entries, after.entries)
        assertEquals(before.currentEntryId, after.currentEntryId)
        assertEquals(4321, after.positionMs)
        assertFalse(after.playWhenReady)
        assertEquals(setOf(c.id, d.id, e.id), upcoming(after).toSet())
        assertEquals(3, upcoming(after).size)
        assertEquals(
            listOf(c.id, d.id, e.id),
            upcoming(step(after, QueueCommand.SetShuffle(false))),
        )
    }

    @Test
    fun latestNextInsertionStaysFirstAcrossShuffle() {
        var q = start(a, b, c)
        q = step(q, QueueCommand.Add(listOf(x), QueueInsertion.NEXT))
        q = step(q, QueueCommand.Add(listOf(y), QueueInsertion.NEXT))
        q = step(q, QueueCommand.Add(listOf(z), QueueInsertion.END))
        assertEquals(listOf(y.id, x.id, b.id, c.id, z.id), upcoming(q))
        q = step(q, QueueCommand.ToggleShuffle)
        assertEquals(listOf(y.id, x.id), upcoming(q).take(2))
        q = step(q, QueueCommand.ToggleShuffle)
        assertEquals(listOf(y.id, x.id, b.id, c.id, z.id), upcoming(q))
        q = step(q, QueueCommand.Next)
        assertEquals(y.id, q.currentEntryId)
        assertEquals(listOf(x.id), q.traversal!!.priority)
    }

    @Test
    fun previousReturnsToVisitedEntryAndProtectsForwardPath() {
        var q = start(a, b, c)
        q = step(q, QueueCommand.Next)
        q = step(q, QueueCommand.Add(listOf(x), QueueInsertion.NEXT))
        q = step(q, QueueCommand.Previous)
        assertEquals(a.id, q.currentEntryId)
        assertEquals(listOf(b.id, x.id, c.id), upcoming(q))
        q = step(q, QueueCommand.ToggleShuffle)
        assertEquals(listOf(b.id, x.id), upcoming(q).take(2))
        assertEquals(b.id, step(q, QueueCommand.Next).currentEntryId)
    }

    @Test
    fun previousUsesStrictThreeSecondBoundary() {
        val visited = step(start(a, b, c), QueueCommand.Next)
        listOf(2999L, 3000L, 3001L).forEach { position ->
            val result =
                QueueReducer.reduce(visited.copy(positionMs = position), QueueCommand.Previous)
            assertEquals(if (position > 3000) b.id else a.id, result.snapshot.currentEntryId)
            assertEquals(0L, result.seekToMs)
        }
    }

    @Test
    fun selectingLaterEntryDoesNotInventSkippedHistory() {
        var q = step(start(a, b, c, d, e), QueueCommand.Select(d.id))
        assertEquals(a.id, QueueOrder.previous(q)?.id)
        assertEquals(listOf(e.id), upcoming(q))
        q = step(step(q, QueueCommand.ToggleShuffle), QueueCommand.ToggleShuffle)
        assertEquals(listOf(e.id), upcoming(q))
        assertEquals(a.id, step(q, QueueCommand.Previous).currentEntryId)
    }

    @Test
    fun startingInMiddleDoesNotVisitEarlierEntries() {
        val q = start(a, b, c, d, at = c)
        assertNull(QueueOrder.previous(q))
        assertEquals(listOf(d.id), upcoming(q))
        assertEquals(c.id, step(q, QueueCommand.Previous).currentEntryId)
    }

    @Test
    fun manualOrderBeforeShuffleBecomesBaseline() {
        var q = step(start(a, b, c, d), QueueCommand.MoveUpcoming(d.id, 0))
        assertEquals(listOf(d.id, b.id, c.id), upcoming(q))
        q = step(step(q, QueueCommand.ToggleShuffle), QueueCommand.ToggleShuffle)
        assertEquals(listOf(d.id, b.id, c.id), upcoming(q))
    }

    @Test
    fun manualMoveWhileShuffledClearsPriorityAndRestoresBaselineOnDisable() {
        var q = step(start(a, b, c), QueueCommand.Add(listOf(x), QueueInsertion.NEXT))
        q = step(q, QueueCommand.ToggleShuffle)
        q = step(q, QueueCommand.MoveUpcoming(x.id, 2))
        assertEquals(x.id, upcoming(q).last())
        assertTrue(q.traversal!!.priority.isEmpty())
        q = step(q, QueueCommand.SetShuffle(false))
        assertEquals(listOf(x.id, b.id, c.id), upcoming(q))
    }

    @Test
    fun replacementKeepsModeAndResetsHistoryAndPriority() {
        var q = step(start(a, b, c), QueueCommand.Next)
        q = step(q, QueueCommand.Add(listOf(x), QueueInsertion.NEXT))
        q = step(q, QueueCommand.ToggleShuffle)
        q = step(q, QueueCommand.Replace(listOf(d, e, y, z), e.id))
        assertTrue(q.shuffleEnabled)
        assertEquals(e.id, q.currentEntryId)
        assertTrue(q.traversal!!.history.isEmpty())
        assertTrue(q.traversal!!.priority.isEmpty())
        assertEquals(setOf(y.id, z.id), upcoming(q).toSet())
    }

    @Test
    fun emptyInsertionSelectsPausedTrackWithoutSeekEffect() {
        val result =
            QueueReducer.reduce(
                QueueSnapshot(),
                QueueCommand.Add(listOf(a, b), QueueInsertion.NEXT),
            )
        assertEquals(a.id, result.snapshot.currentEntryId)
        assertFalse(result.snapshot.playWhenReady)
        assertNull(result.seekToMs)
        assertEquals(listOf(b.id), upcoming(result.snapshot))
    }

    @Test
    fun repeatedTracksRemainDifferentOccurrencesAndRemovalCleansReferences() {
        val duplicate = entry("duplicate", a.trackId.value)
        var q = step(start(a, b, c), QueueCommand.Add(listOf(duplicate), QueueInsertion.NEXT))
        q = step(q, QueueCommand.Next)
        assertEquals(duplicate.id, q.currentEntryId)
        q = step(q, QueueCommand.Remove(duplicate.id))
        assertEquals(b.id, q.currentEntryId)
        assertTrue(QueueOrder.isValid(q))
        assertFalse(q.traversal!!.history.contains(duplicate.id))
        assertFalse(upcoming(q).contains(duplicate.id))
    }

    @Test
    fun repeatedSettingAndShortQueuesDoNotDrawRandom() {
        val random =
            object : Random() {
                var draws = 0

                override fun nextBits(bitCount: Int): Int {
                    draws++
                    return 0
                }
            }
        var q = start(a, b)
        q = QueueReducer.reduce(q, QueueCommand.SetShuffle(true), random).snapshot
        q = QueueReducer.reduce(q, QueueCommand.SetShuffle(true), random).snapshot
        assertTrue(q.shuffleEnabled)
        assertEquals(0, random.draws)
        q = QueueReducer.reduce(q, QueueCommand.Next, random).snapshot
        val end = QueueReducer.reduce(q, QueueCommand.Ended(b.id), random)
        assertTrue(end.stopPlayback)
        assertFalse(end.snapshot.playWhenReady)
        assertTrue(end.snapshot.shuffleEnabled)
        assertEquals(0, random.draws)
    }

    @Test
    fun variedCommandSequencesKeepOccurrenceInvariants() {
        repeat(30) { seed ->
            var q = start(a, b, c, d, e)
            val random = Random(seed)
            repeat(50) { index ->
                val command =
                    when (random.nextInt(6)) {
                        0 -> QueueCommand.ToggleShuffle
                        1 -> QueueCommand.Next
                        2 -> QueueCommand.Previous
                        3 -> QueueCommand.Add(listOf(entry("$seed-$index")), QueueInsertion.NEXT)
                        4 ->
                            QueueOrder.upcoming(q).firstOrNull()?.let { QueueCommand.Remove(it.id) }
                                ?: QueueCommand.SetShuffle(false)
                        else ->
                            QueueOrder.upcoming(q).lastOrNull()?.let {
                                QueueCommand.MoveUpcoming(it.id, 0)
                            } ?: QueueCommand.SetShuffle(true)
                    }
                q = QueueReducer.reduce(q, command, random).snapshot
                assertTrue(QueueOrder.isValid(q), "seed=$seed step=$index $command $q")
            }
        }
    }
}
