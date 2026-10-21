package app.musicplayer.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackStartupTimingTest {
    @Test
    fun firstAudioRecordedOnceForCurrentLoad() {
        val timing = PlaybackStartupTiming()
        timing.start(1, 1000)
        assertEquals(1200L, timing.firstAudio(1, 2200))
        assertNull(timing.firstAudio(1, 2500))
        timing.start(2, 3000)
        assertNull(timing.firstAudio(1, 3100))
        assertEquals(500L, timing.firstAudio(2, 3500))
        timing.clear()
        assertNull(timing.firstAudio(2, 3600))
    }
}
