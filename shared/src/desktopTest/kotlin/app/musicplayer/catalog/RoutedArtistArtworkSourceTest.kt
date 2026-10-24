package app.musicplayer.catalog

import app.musicplayer.artists.ArtistArtworkSource
import app.musicplayer.diagnostics.DiagnosticEvent
import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class RoutedArtistArtworkSourceTest {
    private val source = SourceRef("youtube", "artist", "UC-one")

    private fun context(generation: Long = 1, ready: Boolean = true) =
        NetworkContext(generation, NetworkConfig(NetworkMode.SYSTEM, null, null), ready, true)

    private fun provider(block: suspend () -> AppResult<Artwork?>) =
        object : ArtistArtworkSource {
            override suspend fun fetch(source: SourceRef) = block()
        }

    @Test
    fun unavailableRouteDoesNotCallProvider() = runTest {
        for (ready in listOf(false, true)) {
            val routed =
                RoutedArtistArtworkSource(
                    { if (ready) null else provider { error("Unexpected fetch") } },
                    { context(ready = ready) },
                )
            assertEquals(ErrorCode.NETWORK, routed.fetch(source).errorOrNull()?.code)
        }
    }

    @Test
    fun changedGenerationRejectsLateOldRoute() = runTest {
        var network = context()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<DiagnosticEvent>()
        val routed =
            RoutedArtistArtworkSource(
                {
                    provider {
                        started.complete(Unit)
                        release.await()
                        AppResult.Success(null)
                    }
                },
                { network },
                events::add,
            )
        val result = async { assertFailsWith<CancellationException> { routed.fetch(source) } }
        started.await()
        network = context(2)
        release.complete(Unit)
        result.await()
        assertTrue(events.isEmpty())
    }

    @Test
    fun cancellationIsNotTurnedIntoRetryFailure() = runTest {
        val routed =
            RoutedArtistArtworkSource(
                { provider { throw CancellationException("stopped") } },
                { context() },
            )
        assertFailsWith<CancellationException> { routed.fetch(source) }
    }

    @Test
    fun diagnosticsContainOnlyOperationCodeAndTiming() = runTest {
        val events = mutableListOf<DiagnosticEvent>()
        val routed =
            RoutedArtistArtworkSource(
                {
                    provider {
                        AppResult.Failure(
                            AppError(ErrorCode.NETWORK, "private-provider-detail", true)
                        )
                    }
                },
                { context() },
                events::add,
            )
        assertEquals(ErrorCode.NETWORK, routed.fetch(source).errorOrNull()?.code)
        val event = events.single()
        assertTrue(event.operationId.isNotBlank())
        assertEquals(ErrorCode.NETWORK, event.errorCode)
        assertTrue(event.elapsedMs >= 0)
        assertNull(event.sourceId)
        assertNull(event.transport)
        assertFalse(event.toString().contains("private-provider-detail"))
    }
}
