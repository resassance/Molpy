package app.musicplayer.playback

import app.musicplayer.model.AudioMetadata
import org.junit.Assert.*
import org.junit.Test

class VolumeApplicationTest {
    @Test
    fun unknownSourceResetsBeforePrepare() {
        val events = mutableListOf<String>()
        val volume = VolumeApplication({ events += "volume:$it" })
        volume.load(AudioMetadata(6.0))
        volume.load(null)
        events += "source:B"
        events += "prepare"
        assertEquals(listOf("volume:1.0", "source:B", "prepare"), events.takeLast(3))
    }

    @Test
    fun preferencesReapplyCurrentGainAndResetDropsOldMetadata() {
        var gain = 0f
        val volume = VolumeApplication({ gain = it })
        volume.load(AudioMetadata(6.0))
        assertEquals(0.501187f, gain, 0.000001f)
        volume.setEnabled(false)
        assertEquals(1f, gain)
        volume.setEnabled(true)
        assertEquals(0.501187f, gain, 0.000001f)
        volume.reset()
        assertEquals(1f, gain)
        volume.setEnabled(true)
        assertEquals(1f, gain)
    }
}
