package app.musicplayer.queue

import app.musicplayer.model.*
import app.musicplayer.playback.PlaybackPort
import kotlinx.coroutines.flow.StateFlow

/** Normalized service connection: UI and media controls share the same queue. */
interface QueuePlaybackPort : PlaybackPort {
    suspend fun enqueue(
        track: Track,
        position: QueueInsertion = QueueInsertion.END,
    ): AppResult<Unit>

    suspend fun playList(tracks: List<Track>, startIndex: Int): AppResult<Unit>

    suspend fun playPublicPlaylist(request: PlaylistPlaybackRequest): AppResult<Unit> =
        AppResult.Failure(AppError(ErrorCode.SOURCE_UNAVAILABLE, "queue.source.unsupported", false))

    suspend fun retryQueueSource(): AppResult<Unit> =
        AppResult.Failure(AppError(ErrorCode.SOURCE_UNAVAILABLE, "queue.source.unsupported", false))

    val queue: StateFlow<QueueSnapshot>

    suspend fun dispatch(command: QueueCommand)
}
