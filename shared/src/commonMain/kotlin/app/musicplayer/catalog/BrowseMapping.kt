package app.musicplayer.catalog

import app.musicplayer.model.*

internal data class BrowseTrackContext(
    val album: AlbumRef? = null,
    val artists: List<ArtistRef> = emptyList(),
    val artwork: Artwork? = null,
)

internal data class BrowseRequest(
    val browseId: String? = null,
    val params: String? = null,
    val continuation: String? = null,
)

internal data class MappedCatalogPage<T>(val items: List<T>, val next: BrowseRequest?)

internal data class MappedAlbum(
    val album: AlbumRef,
    val artists: List<ArtistRef>,
    val artwork: Artwork,
    val tracks: MappedCatalogPage<Track>,
)

internal data class MappedArtist(
    val artist: ArtistRef,
    val artwork: Artwork?,
    val tracks: MappedCatalogPage<Track>,
    val albums: MappedCatalogPage<AlbumCard>,
    val singles: MappedCatalogPage<AlbumCard>,
)
