package app.musicplayer.catalog

import app.musicplayer.diagnostics.DiagnosticEvent
import app.musicplayer.model.*
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException

class RoutedBrowseCatalog(
    private val active: () -> BrowseCatalog?,
    private val network: () -> NetworkContext,
    private val diagnostics: (DiagnosticEvent) -> Unit = {},
) : BrowseCatalog {
    override suspend fun album(ref: AlbumRef) = routed { it.album(ref) }

    override suspend fun artist(ref: ArtistRef) = routed { it.artist(ref) }

    override suspend fun moreTracks(cursor: CatalogCursor) = routed { it.moreTracks(cursor) }

    override suspend fun moreAlbums(cursor: CatalogCursor) = routed { it.moreAlbums(cursor) }

    override suspend fun playlist(ref: PublicPlaylistRef, refresh: Boolean) =
        routed(
            operation = "catalog.playlist",
            stage = "initial",
            count = { page: PublicPlaylistCatalog -> page.tracks.items.size },
        ) {
            it.playlist(ref, refresh)
        }

    override suspend fun morePlaylist(cursor: CatalogCursor) =
        routed(
            operation = "catalog.playlist.more",
            stage = "continuation",
            count = { page: CatalogPage<PlaylistOccurrence> -> page.items.size },
        ) {
            it.morePlaylist(cursor)
        }

    private suspend fun <T> routed(
        operation: String? = null,
        stage: String = "initial",
        count: ((T) -> Int)? = null,
        action: suspend (BrowseCatalog) -> AppResult<T>,
    ): AppResult<T> {
        val started = TimeSource.Monotonic.markNow()
        val context = network()
        val provider = active()
        if (!context.ready || provider == null) {
            val failure =
                AppResult.Failure(AppError(ErrorCode.NETWORK, "catalog.browse.route", true))
            if (network().generation == context.generation)
                record(operation, stage, count, failure, started.elapsedNow().inWholeMilliseconds)
            return failure
        }
        val result = action(provider)
        val after = network()
        if (!after.ready || after.generation != context.generation)
            throw CancellationException("Network generation changed")
        record(operation, stage, count, result, started.elapsedNow().inWholeMilliseconds)
        return result
    }

    private fun <T> record(
        operation: String?,
        stage: String,
        count: ((T) -> Int)?,
        result: AppResult<T>,
        elapsedMs: Long,
    ) {
        if (operation == null) return
        diagnostics(
            DiagnosticEvent(
                operationId = operation,
                stage = stage,
                providerVersion = "innertubex-0.10.0",
                sourceId = null,
                transport = null,
                errorCode = result.errorOrNull()?.code,
                elapsedMs = elapsedMs,
                filter = SearchFilter.PLAYLISTS.name,
                itemCount =
                    if (result is AppResult.Success) count?.invoke(result.value) ?: 0 else 0,
                pageCount = if (result is AppResult.Success) 1 else 0,
            )
        )
    }
}
