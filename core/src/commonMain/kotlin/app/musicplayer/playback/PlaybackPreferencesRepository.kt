package app.musicplayer.playback

import kotlinx.coroutines.flow.StateFlow

interface PlaybackPreferencesRepository {
    val normalizationEnabled: StateFlow<Boolean>

    suspend fun setNormalizationEnabled(enabled: Boolean)
}
