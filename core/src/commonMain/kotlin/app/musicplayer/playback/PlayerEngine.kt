package app.musicplayer.playback

import app.musicplayer.model.*
import kotlinx.coroutines.flow.StateFlow

/** Commands and callbacks are confined to the owner's dispatcher (Android main thread). */
interface PlayerEngine {
    val snapshot: StateFlow<PlaybackSnapshot>
    suspend fun load(entry: QueueEntry, source: PlayableSource, positionMs: Long, playWhenReady: Boolean): AppResult<Unit>
    fun play()
    fun pause()
    fun seek(positionMs: Long)
    fun stop()
    fun release()
}
