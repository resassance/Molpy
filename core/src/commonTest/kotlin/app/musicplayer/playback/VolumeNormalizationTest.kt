package app.musicplayer.playback

import app.musicplayer.model.AudioMetadata
import kotlin.test.*

class VolumeNormalizationTest {
    @Test
    fun positiveOffsetOnlyAttenuates() {
        assertEquals(0.501187f, normalizationGain(AudioMetadata(6.0), true), 0.000001f)
        assertEquals(1f, normalizationGain(AudioMetadata(-6.0), true))
        assertEquals(1f, normalizationGain(AudioMetadata(0.0), true))
        assertEquals(0.0316228f, normalizationGain(AudioMetadata(30.0), true), 0.000001f)
        assertEquals(1f, normalizationGain(AudioMetadata(-30.0), true))
    }

    @Test
    fun unknownInvalidOrDisabledMetadataLeavesUnityGain() {
        assertEquals(1f, normalizationGain(null, true))
        for (value in
            listOf(
                null,
                Double.NaN,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                31.0,
                -31.0,
            )) {
            assertEquals(1f, normalizationGain(AudioMetadata(value), true))
        }
        assertEquals(1f, normalizationGain(AudioMetadata(6.0), false))
    }
}
