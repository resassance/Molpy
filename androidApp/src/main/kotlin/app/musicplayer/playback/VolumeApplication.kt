package app.musicplayer.playback

import app.musicplayer.model.AudioMetadata

internal class VolumeApplication(private val setGain: (Float) -> Unit, enabled: Boolean = true) {
    private var normalizing = enabled
    private var metadata: AudioMetadata? = null

    fun load(metadata: AudioMetadata?, enabled: Boolean = normalizing) {
        this.metadata = metadata
        normalizing = enabled
        apply()
    }

    fun setEnabled(enabled: Boolean) {
        normalizing = enabled
        apply()
    }

    fun reset() {
        metadata = null
        apply()
    }

    private fun apply() = setGain(normalizationGain(metadata, normalizing))
}
