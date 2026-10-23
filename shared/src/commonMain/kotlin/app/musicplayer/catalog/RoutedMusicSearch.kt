package app.musicplayer.catalog

import app.musicplayer.model.*
import kotlinx.coroutines.CancellationException

class RoutedMusicSearch(
    private val active: () -> MusicSearchPort?,
    private val network: () -> NetworkContext,
) : MusicSearchPort {
    override suspend fun search(query: String, filter: SearchFilter) = routed {
        it.search(query, filter)
    }

    override suspend fun more(cursor: CatalogCursor) = routed { it.more(cursor) }

    private suspend fun <T> routed(
        action: suspend (MusicSearchPort) -> AppResult<T>
    ): AppResult<T> {
        val before = network()
        val provider = active()
        if (!before.ready || provider == null)
            return AppResult.Failure(AppError(ErrorCode.NETWORK, "catalog.search.route", true))
        val result = action(provider)
        if (!network().ready || network().generation != before.generation)
            throw CancellationException("Network generation changed")
        return result
    }
}
