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
    val trackMotion: TrackMotion = TrackMotion.REPLACE,
) {
    val effectiveDurationMs: Long?
        get() =
            snapshot.durationMs?.takeIf { it > 0 }
                ?: currentTrack?.let { track ->
                    val candidate =
                        if (snapshot.candidateId != null)
                            track.candidates.firstOrNull { it.id == snapshot.candidateId }
                        else track.candidates.firstOrNull()
                    candidate?.durationMs?.takeIf { it > 0 } ?: track.durationMs?.takeIf { it > 0 }
                }

    val currentTrack: Track?
        get() {
            val active = snapshot.track ?: return snapshot.current?.trackId?.let(queueTracks::get)
            val saved = queueTracks[active.id] ?: return active
            return saved.candidates.fold(active) { track, candidate ->
                candidate.durationMs
                    ?.takeIf { it > 0 }
                    ?.let {
                        track.withMissingDuration(candidate.source, it)
                    } ?: track
            }
        }
}
