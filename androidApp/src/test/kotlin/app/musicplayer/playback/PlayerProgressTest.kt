package app.musicplayer.playback

import org.junit.Assert.*
import org.junit.Test

class PlayerProgressTest {
    @Test
    fun unknownDurationAndBufferRemainUnknown() {
        val result = samplePlayerProgress(-1, -9223372036854775807L, -9223372036854775807L)
        assertEquals(0L, result.positionMs)
        assertNull(result.durationMs)
        assertNull(result.bufferedPositionMs)
    }

    @Test
    fun realBufferIsAvailableBeforePlaybackAndClampedToDuration() {
        val result = samplePlayerProgress(0, 188000, 200000)
        assertEquals(188000L, result.bufferedPositionMs)
        assertEquals(0L, result.positionMs)
    }

    @Test
    fun unknownDurationDoesNotFabricatePercentageOrDiscardActualBuffer() {
        val result = samplePlayerProgress(1000, 0, 5000)
        assertNull(result.durationMs)
        assertEquals(5000L, result.bufferedPositionMs)
    }
}
