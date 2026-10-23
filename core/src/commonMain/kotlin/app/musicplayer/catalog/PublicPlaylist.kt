package app.musicplayer.catalog

import app.musicplayer.model.*

data class PublicPlaylistRef(val title: String, val source: SourceRef)

data class PublicPlaylistCard(
    val ref: PublicPlaylistRef,
    val artwork: Artwork?,
    val owner: String?,
    val count: Int?,
)

data class PlaylistRowIdentity(
    val ordinal: Int,
    val itemId: String?,
    val playbackSource: SourceRef?,
)

data class PlaylistOccurrence(
    val identity: PlaylistRowIdentity,
    val track: Track?,
    val unavailable: Boolean,
)

data class PublicPlaylistCatalog(
    val card: PublicPlaylistCard,
    val tracks: CatalogPage<PlaylistOccurrence>,
)
