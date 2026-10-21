package app.musicplayer.model

/** A positive offset calls for attenuation; this is not an absolute LUFS measurement. */
data class AudioMetadata(val relativeLoudnessDb: Double?) {
    companion object {
        fun fromSourceOffset(value: Double?) =
            AudioMetadata(value?.takeIf { it.isFinite() && it in -30.0..30.0 })
    }
}
