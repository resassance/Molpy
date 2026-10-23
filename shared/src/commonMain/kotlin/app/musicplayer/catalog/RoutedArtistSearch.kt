package app.musicplayer.catalog

import app.musicplayer.artists.*
import app.musicplayer.model.*
import kotlinx.coroutines.CancellationException

class RoutedArtistSearch(
    private val active: () -> ArtistSearchPort?,
    private val network: () -> NetworkContext,
) : ArtistSearchPort {
    override suspend fun search(query: String) = routed { it.search(query) }

    override suspend fun validate(hit: ArtistCatalogHit) = routed { it.validate(hit) }

    private suspend fun <T> routed(
        action: suspend (ArtistSearchPort) -> AppResult<T>
    ): AppResult<T> {
        val context = network()
        val provider = active()
        if (!context.ready || provider == null)
            return AppResult.Failure(AppError(ErrorCode.NETWORK, "catalog.artist.route", true))
        val result = action(provider)
        if (!network().ready || network().generation != context.generation)
            throw CancellationException("Network generation changed")
        return result
    }
}
