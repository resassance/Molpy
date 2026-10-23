package app.musicplayer.catalog

import app.musicplayer.model.*

interface BrowseCatalog {
    suspend fun album(ref: AlbumRef): AppResult<AlbumCatalog>

    suspend fun artist(ref: ArtistRef): AppResult<ArtistCatalog>

    suspend fun moreTracks(cursor: CatalogCursor): AppResult<CatalogPage<Track>>

    suspend fun moreAlbums(cursor: CatalogCursor): AppResult<CatalogPage<AlbumCard>>

    suspend fun playlist(
        ref: PublicPlaylistRef,
        refresh: Boolean = false,
    ): AppResult<PublicPlaylistCatalog> =
        AppResult.Failure(
            AppError(ErrorCode.SOURCE_UNAVAILABLE, "catalog.playlist.unsupported", false)
        )

    suspend fun morePlaylist(cursor: CatalogCursor): AppResult<CatalogPage<PlaylistOccurrence>> =
        AppResult.Failure(
            AppError(ErrorCode.SOURCE_UNAVAILABLE, "catalog.playlist.unsupported", false)
        )
}
