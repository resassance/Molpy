package app.musicplayer.queue

import app.musicplayer.model.QueueSnapshot
import app.musicplayer.playback.PlaybackPort
import kotlinx.coroutines.flow.StateFlow

/** Normalized service connection: UI and media controls share the same queue. */
interface QueuePlaybackPort : PlaybackPort {
    val queue: StateFlow<QueueSnapshot>
    suspend fun dispatch(command: QueueCommand)
}
