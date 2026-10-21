package app.musicplayer.catalog

import app.musicplayer.model.*

@JvmInline
value class CatalogCursor(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

data class CatalogPage<T>(val items: List<T>, val next: CatalogCursor?)

data class AlbumCard(val album: AlbumRef, val artists: List<ArtistRef>, val artwork: Artwork)

data class AlbumCatalog(
    val album: AlbumRef,
    val artists: List<ArtistRef>,
    val artwork: Artwork,
    val tracks: CatalogPage<Track>,
)

data class ArtistCatalog(
    val artist: ArtistRef,
    val artwork: Artwork?,
    val tracks: CatalogPage<Track>,
    val albums: CatalogPage<AlbumCard>,
    val singles: CatalogPage<AlbumCard>,
)
