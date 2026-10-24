package app.musicplayer.catalog

import app.musicplayer.artists.ArtistArtworkSource
import app.musicplayer.diagnostics.DiagnosticEvent
import app.musicplayer.model.*
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException

class RoutedArtistArtworkSource(
    private val active: () -> ArtistArtworkSource?,
    private val network: () -> NetworkContext,
    private val diagnostics: (DiagnosticEvent) -> Unit = {},
) : ArtistArtworkSource {
    override suspend fun fetch(source: SourceRef): AppResult<Artwork?> {
        val started = TimeSource.Monotonic.markNow()
        val before = network()
        val provider = active()
        val result =
            if (!before.ready || provider == null)
                AppResult.Failure(AppError(ErrorCode.NETWORK, "catalog.artist.artwork.route", true))
            else provider.fetch(source)
        val after = network()
        if (after.generation != before.generation || (before.ready && !after.ready))
            throw CancellationException("Network generation changed")
        diagnostics(
            DiagnosticEvent(
                "catalog.artist.artwork",
                "header",
                "innertubex-0.10.0",
                null,
                null,
                result.errorOrNull()?.code,
                started.elapsedNow().inWholeMilliseconds,
            )
        )
        return result
    }
}
