package app.musicplayer.artwork

import app.musicplayer.model.*
import coil3.PlatformContext
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import io.ktor.client.request.get
import kotlin.test.*

class ArtworkLoaderTest {
    @Test fun fetchUsesSuppliedClientAndReturnsSyntheticImageBytes() = runTest {
        var requests = 0
        val bytes = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aB9sAAAAASUVORK5CYII=")
        val http = HttpClient(MockEngine { requests++; respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png")) })
        try {
            val loader = ArtworkLoader(PlatformContext.INSTANCE, http)
            val result = assertNotNull(loader.fetch(Artwork(ArtworkKind.RELEASE, "https://images.test/cover.png", null)).valueOrNull())
            assertContentEquals(bytes, result.bytes)
            assertEquals("image/png", result.mimeType)
            assertEquals(1, requests)
            loader.imageLoader.shutdown()
        } finally { http.close() }
    }

    @Test fun coilFailureReturnsConfiguredPlaceholderUsingInjectedNetwork() = runTest {
        var requests = 0
        val http = HttpClient(MockEngine { requests++; respond("unavailable", HttpStatusCode.ServiceUnavailable) })
        try {
            val loader = ArtworkLoader(PlatformContext.INSTANCE, http)
            val result = assertIs<ErrorResult>(loader.imageLoader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data("https://images.test/failure.png").build()))
            assertNotNull(result.image)
            assertEquals(1, requests)
            loader.imageLoader.shutdown()
        } finally { http.close() }
    }

    @Test fun failureIsNormalizedAndPlaceholderDoesNotFetch() = runTest {
        var requests = 0
        val http = HttpClient(MockEngine { requests++; respond("bad", HttpStatusCode.ServiceUnavailable) })
        try {
            val loader = ArtworkLoader(PlatformContext.INSTANCE, http)
            assertIs<AppResult.Failure>(loader.fetch(Artwork(ArtworkKind.VIDEO, "https://images.test/failure", null)))
            assertIs<AppResult.Failure>(loader.fetch(Artwork(ArtworkKind.PLACEHOLDER, null, null)))
            assertEquals(1, requests)
            loader.imageLoader.shutdown()
        } finally { http.close() }
    }

    @Test fun cancellationPropagatesAndLoaderShutdownKeepsCallerClientOpen() = runTest {
        val http = HttpClient(MockEngine { throw CancellationException("fixture") })
        val loader = ArtworkLoader(PlatformContext.INSTANCE, http)
        try {
            assertFailsWith<CancellationException> { loader.fetch(Artwork(ArtworkKind.RELEASE, "https://images.test/cancel", null)) }
        } finally { loader.imageLoader.shutdown(); http.close() }
        val reusable = HttpClient(MockEngine { respond("still open") })
        try {
            ArtworkLoader(PlatformContext.INSTANCE, reusable).imageLoader.shutdown()
            assertEquals(HttpStatusCode.OK, reusable.get("https://images.test/still-open").status)
        } finally { reusable.close() }
    }
}
