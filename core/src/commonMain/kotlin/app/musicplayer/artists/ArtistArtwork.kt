package app.musicplayer.artists

import app.musicplayer.model.Artwork
import app.musicplayer.model.SourceRef

enum class ArtistArtworkOutcome {
    AVAILABLE,
    ABSENT,
    RETRY,
}

data class ArtistArtworkEntry(
    val source: SourceRef,
    val artwork: Artwork?,
    val checkedAtMs: Long,
    val refreshAfterMs: Long,
    val outcome: ArtistArtworkOutcome,
)
