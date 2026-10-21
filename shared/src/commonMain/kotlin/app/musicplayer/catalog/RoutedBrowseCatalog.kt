package app.musicplayer.catalog

import app.musicplayer.model.*
import kotlinx.coroutines.CancellationException

class RoutedBrowseCatalog(
    private val active: () -> BrowseCatalog?,
    private val network: () -> NetworkContext,
) : BrowseCatalog {
    override suspend fun album(ref: AlbumRef) = routed { it.album(ref) }

    override suspend fun artist(ref: ArtistRef) = routed { it.artist(ref) }

    override suspend fun moreTracks(cursor: CatalogCursor) = routed { it.moreTracks(cursor) }

    override suspend fun moreAlbums(cursor: CatalogCursor) = routed { it.moreAlbums(cursor) }

    private suspend fun <T> routed(action: suspend (BrowseCatalog) -> AppResult<T>): AppResult<T> {
        val context = network()
        val provider = active()
        if (!context.ready || provider == null)
            return AppResult.Failure(AppError(ErrorCode.NETWORK, "catalog.browse.route", true))
        val result = action(provider)
        if (!network().ready || network().generation != context.generation)
            throw CancellationException("Network generation changed")
        return result
    }
}
