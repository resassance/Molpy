package app.musicplayer.search

import app.musicplayer.catalog.CatalogPort
import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchLatencyTest {
    @Test
    fun slowEnrichmentCannotHoldTheSearchOpenIndefinitely() = runTest {
        val videos = (0..7).map { track("video-$it", title = "Band - Song $it", tentative = true) }
        var active = 0
        var peak = 0
        val catalog =
            object : CatalogPort {
                override suspend fun searchSongs(query: String) =
                    AppResult.Success(emptyList<Track>())

                override suspend fun searchVideos(query: String) = AppResult.Success(videos)

                override suspend fun findMusicMatches(track: Track): AppResult<List<Track>> {
                    active++
                    peak = maxOf(peak, active)
                    try {
                        delay(10_000)
                        return AppResult.Success(emptyList())
                    } finally {
                        active--
                    }
                }
            }
        val result = SearchCoordinator(catalog).search("Song").valueOrNull()!!
        assertEquals(videos.map { it.id }, result.tracks.map { it.id })
        assertTrue(
            testScheduler.currentTime <= 3_000,
            "Optional metadata held results for ${testScheduler.currentTime} ms",
        )
        assertTrue(peak in 2..3, "Enrichment must overlap without flooding the provider: $peak")
        assertEquals(0, active)
    }
}
