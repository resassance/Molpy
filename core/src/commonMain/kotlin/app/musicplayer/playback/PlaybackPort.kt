package app.musicplayer.playback

import app.musicplayer.model.*
import kotlinx.coroutines.flow.StateFlow

/** Normalized commands for a service-owned player. A UI client never releases playback resources. */
interface PlaybackPort {
    val snapshot: StateFlow<PlaybackSnapshot>
    suspend fun select(track: Track, entry: QueueEntry, positionMs: Long = 0, playWhenReady: Boolean = true): AppResult<Unit>
    fun play()
    fun pause()
    fun seek(positionMs: Long)
    fun stop()
}
