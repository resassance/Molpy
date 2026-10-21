package app.musicplayer.listening

import app.musicplayer.fixtures.Fixtures
import app.musicplayer.model.*
import kotlin.test.*

internal fun listeningSnapshot(
    attempt: Long = 1,
    status: PlayerStatus = PlayerStatus.PLAYING,
    id: String = "a",
    position: Long = 0,
    load: Long = 1,
): PlaybackSnapshot {
    val track = Fixtures.track(id, sourceId = id)
    return PlaybackSnapshot(
        status,
        QueueEntry(QueueEntryId("entry-$id"), track.id),
        track,
        position,
        playWhenReady = status == PlayerStatus.PLAYING,
        loadId = load,
        attemptId = attempt,
    )
}

class ListeningAccumulatorTest {
    @Test
    fun pauseBufferAndSeekCountOnlyElapsedPlayingIntervals() {
        val accumulator = ListeningAccumulator("owner")
        val checkpoints = mutableListOf<ListeningCheckpoint>()
        checkpoints += accumulator.observe(listeningSnapshot(), 0, 100000)
        checkpoints +=
            accumulator.observe(listeningSnapshot(status = PlayerStatus.PAUSED), 5000, 105000)
        checkpoints +=
            accumulator.observe(listeningSnapshot(status = PlayerStatus.BUFFERING), 10000, 110000)
        checkpoints += accumulator.observe(listeningSnapshot(), 15000, 115000)
        accumulator.observe(listeningSnapshot(position = 90000), 17000, 117000)
        checkpoints +=
            accumulator.observe(listeningSnapshot(status = PlayerStatus.PAUSED), 18000, 118000)
        assertEquals(8000L, checkpoints.last().playedMs)
        assertEquals(1, checkpoints.map { it.sessionId }.distinct().size)
        assertEquals(118000L, checkpoints.last().lastPlayedAtMs)
    }

    @Test
    fun recoveryRetainsSessionReplayCreatesNewAndOldAttemptCannotTakeOver() {
        val accumulator = ListeningAccumulator("owner")
        val first = accumulator.observe(listeningSnapshot(), 0, 100).single()
        accumulator.observe(listeningSnapshot(status = PlayerStatus.RESOLVING), 1000, 1100)
        accumulator.observe(listeningSnapshot(load = 2), 4000, 4100)
        val recovered = accumulator.checkpoint(5000).single()
        assertEquals(first.sessionId, recovered.sessionId)
        assertEquals(2000L, recovered.playedMs)
        val replay = accumulator.observe(listeningSnapshot(attempt = 2), 6000, 6100)
        assertNotEquals(first.sessionId, replay.last().sessionId)
        assertEquals(0L, replay.last().playedMs)
        assertTrue(
            accumulator.observe(listeningSnapshot(attempt = 1, id = "old"), 7000, 7100).isEmpty()
        )
        assertEquals("a", accumulator.checkpoint(8000).last().track.id.value)
    }

    @Test
    fun pausedRestoreDoesNotInventSessionAndClockRegressionDoesNotAddTime() {
        val accumulator = ListeningAccumulator("owner")
        assertTrue(
            accumulator
                .observe(
                    listeningSnapshot(status = PlayerStatus.PAUSED).copy(attemptId = null),
                    0,
                    100,
                )
                .isEmpty()
        )
        assertTrue(accumulator.checkpoint(1000).isEmpty())
        accumulator.observe(listeningSnapshot(), 2000, 2100)
        assertTrue(accumulator.checkpoint(1900).isEmpty())
        val paused =
            accumulator.observe(listeningSnapshot(status = PlayerStatus.PAUSED), 2000, 2200)
        assertEquals(100L, paused.single().playedMs)
        assertTrue(accumulator.checkpoint(99999).isEmpty())
    }
}
