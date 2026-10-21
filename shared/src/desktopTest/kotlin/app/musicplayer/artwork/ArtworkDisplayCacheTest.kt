package app.musicplayer.artwork

import app.musicplayer.model.*
import coil3.ColorImage
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.memory.MemoryCache
import coil3.request.SuccessResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlin.test.*

class ArtworkDisplayCacheTest {
    private val a = Artwork(ArtworkKind.RELEASE, "https://images.test/a.png", null)
    private val b = a.copy(remoteUrl = "https://images.test/b.png")

    private fun withLoader(block: (ArtworkLoader) -> Unit) {
        val http = HttpClient(MockEngine { error("Display-cache operations must not fetch") })
        val loader = ArtworkLoader(PlatformContext.INSTANCE, http)
        try { block(loader) } finally { loader.imageLoader.shutdown(); http.close() }
    }

    private fun success(loader: ArtworkLoader, artwork: Artwork, generation: Long): SuccessResult {
        val request = loader.displayRequest(artwork, generation)
        val key = MemoryCache.Key(requireNotNull(request.data).toString(), request.memoryCacheKeyExtras)
        val image = ColorImage(0xffee2222.toInt())
        assertNotNull(loader.imageLoader.memoryCache)[key] = MemoryCache.Value(image)
        return SuccessResult(image, request, DataSource.MEMORY_CACHE, key, null, false, false)
    }

    @Test fun onlySameArtworkOnSameRouteCanBePlaceholder() = withLoader { loader ->
        val result = success(loader, a, 1)
        loader.rememberDisplayResult(a, 1, result)
        assertEquals(result.memoryCacheKey, loader.displayRequest(a, 1).placeholderMemoryCacheKey)
        assertNull(loader.displayRequest(b, 1).placeholderMemoryCacheKey)
        assertNull(loader.displayRequest(a, 2).placeholderMemoryCacheKey)
    }

    @Test fun lateResultCannotBeRememberedForAnotherArtworkOrRoute() = withLoader { loader ->
        val result = success(loader, a, 1)
        loader.rememberDisplayResult(b, 1, result)
        loader.rememberDisplayResult(a, 2, result)
        assertNull(loader.displayRequest(b, 1).placeholderMemoryCacheKey)
        assertNull(loader.displayRequest(a, 2).placeholderMemoryCacheKey)
    }

    @Test fun routeChangeKeepsLocalCoverAndEvictsRemoteFallback() = withLoader { loader ->
        val local = a.copy(localKey = "/tmp/molpy-artwork-fixture.png")
        val offline = success(loader, local, 1)
        val remote = success(loader, a, 1)
        loader.rememberDisplayResult(local, 1, offline)
        loader.rememberDisplayResult(a, 1, remote)
        loader.networkInvalidated()
        assertEquals(offline.memoryCacheKey, loader.displayRequest(local, 2).placeholderMemoryCacheKey)
        assertNull(loader.displayRequest(a, 1).placeholderMemoryCacheKey)
        assertNull(loader.imageLoader.memoryCache?.get(assertNotNull(remote.memoryCacheKey)))
    }

    @Test fun rememberedArtworkIdentitiesAreBounded() = withLoader { loader ->
        repeat(33) { index ->
            val artwork = a.copy(remoteUrl = "https://images.test/$index.png")
            loader.rememberDisplayResult(artwork, 1, success(loader, artwork, 1))
        }
        assertNull(loader.displayRequest(a.copy(remoteUrl = "https://images.test/0.png"), 1).placeholderMemoryCacheKey)
        assertNotNull(loader.displayRequest(a.copy(remoteUrl = "https://images.test/32.png"), 1).placeholderMemoryCacheKey)
    }
}
