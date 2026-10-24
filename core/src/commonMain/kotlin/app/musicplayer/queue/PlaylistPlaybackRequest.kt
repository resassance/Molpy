package app.musicplayer.queue

import app.musicplayer.catalog.*

data class PlaylistPlaybackRequest(
    val ref: PublicPlaylistRef,
    val rows: List<PlaylistOccurrence>,
    val selectedOrdinal: Int,
    val next: CatalogCursor?,
    val shuffle: Boolean = false,
)
