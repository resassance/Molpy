package app.musicplayer.playback

import app.musicplayer.model.AudioMetadata
import kotlin.math.pow

fun normalizationGain(metadata: AudioMetadata?, enabled: Boolean): Float {
    if (!enabled) return 1f
    val offset =
        AudioMetadata.fromSourceOffset(metadata?.relativeLoudnessDb).relativeLoudnessDb ?: return 1f
    return minOf(1.0, 10.0.pow(-offset / 20.0)).toFloat()
}
