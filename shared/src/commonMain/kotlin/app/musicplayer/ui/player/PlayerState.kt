package app.musicplayer.ui.player

import app.musicplayer.model.*

data class PlayerState(
    val snapshot: PlaybackSnapshot = PlaybackSnapshot(),
    val queue: QueueSnapshot = QueueSnapshot(),
    val queueTracks: Map<TrackId, Track> = emptyMap(),
    val lyrics: LyricsLookup? = null,
    val loadingLyrics: Boolean = false,
    val lyricsError: AppError? = null,
    val commandError: AppError? = null,
    val repositoryError: AppError? = null,
) {
    val currentTrack: Track?
        get() = snapshot.track ?: snapshot.current?.trackId?.let(queueTracks::get)
}
