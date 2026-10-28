package app.musicplayer.catalog

import app.musicplayer.diagnostics.DiagnosticEvent
import app.musicplayer.library.LibraryRepository
import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class TrackMetadataLoader(
    private val library: LibraryRepository,
    private val catalog: CatalogPort,
    private val diagnostics: (DiagnosticEvent) -> Unit = {},
) {
    private val requests = Semaphore(2)

    suspend fun load(
        tracks: List<Track>,
        allowRemote: Boolean = true,
        onUpdated: (List<Track>) -> Unit,
    ) {
        val unique = tracks.distinctBy { it.id }
        if (unique.isEmpty()) return
        var current =
            storage {
                val changed = unique.filter {
                    val saved = library.getTrack(it.id)
                    saved == null ||
                        (it != saved && it.provenance.fetchedAtMs >= saved.provenance.fetchedAtMs)
                }
                library.upsertAll(changed)
                unique.map { original ->
                    var saved = library.getTrack(original.id) ?: original
                    val candidate = saved.candidates.firstOrNull()
                    val known = candidate?.durationMs?.takeIf { it > 0 }
                    if (((saved.durationMs ?: 0) <= 0) && candidate != null && known != null)
                        saved =
                            library.fillMissingDuration(saved.id, candidate.source, known) ?: saved
                    saved
                }
            } ?: return
        currentCoroutineContext().ensureActive()
        onUpdated(current)
        if (!allowRemote) return
        val missing =
            current
                .flatMap { track ->
                    track.candidates.filter { (it.durationMs ?: 0) <= 0 }.map { it.source }
                }
                .distinct()
        for (batch in missing.chunked(50)) {
            val fetched =
                try {
                    requests.withPermit { withTimeoutOrNull(15_000) { catalog.durations(batch) } }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    AppResult.Failure(AppError(ErrorCode.NETWORK, "metadata.duration", true))
                }
            currentCoroutineContext().ensureActive()
            val values = fetched?.valueOrNull()
            if (values == null) {
                diagnostics(
                    DiagnosticEvent(
                        "metadata",
                        "duration",
                        null,
                        null,
                        null,
                        fetched?.errorOrNull()?.code ?: ErrorCode.NETWORK,
                        0,
                    )
                )
                continue
            }
            current =
                storage {
                    current.map { track ->
                        var updated = track
                        for (candidate in track.candidates) {
                            val duration = values[candidate.source]?.takeIf { it > 0 } ?: continue
                            updated =
                                library.fillMissingDuration(track.id, candidate.source, duration)
                                    ?: updated
                        }
                        updated
                    }
                } ?: return
            currentCoroutineContext().ensureActive()
            onUpdated(current)
        }
    }

    private suspend fun <T> storage(action: suspend () -> T): T? =
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diagnostics(
                DiagnosticEvent("metadata", "storage", null, null, null, ErrorCode.DATABASE, 0)
            )
            null
        }
}
