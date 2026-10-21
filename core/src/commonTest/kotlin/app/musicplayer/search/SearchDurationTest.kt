package app.musicplayer.search

import app.musicplayer.catalog.CatalogPort
import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SearchDurationTest {
    @Test
    fun durationArrivesAfterFirstResultsWithoutResolvingAudio() = runTest {
        val song = track(duration = null)
        val gate = CompletableDeferred<Unit>()
        val snapshots = mutableListOf<SearchResults>()
        var requests = 0
        val catalog =
            object : CatalogPort {
                override suspend fun searchSongs(query: String) = AppResult.Success(listOf(song))

                override suspend fun searchVideos(query: String): AppResult<List<Track>> =
                    error("No video fallback")

                override suspend fun findMusicMatches(track: Track): AppResult<List<Track>> =
                    error("No match lookup")

                override suspend fun durations(
                    sources: List<SourceRef>
                ): AppResult<Map<SourceRef, Long>> {
                    requests++
                    gate.await()
                    return AppResult.Success(mapOf(song.candidates.first().source to 188_000L))
                }
            }
        val pending = async { SearchCoordinator(catalog).search("Numb", snapshots::add) }
        runCurrent()
        assertEquals(listOf(song.id), snapshots.first().tracks.map { it.id })
        assertNull(snapshots.first().tracks.single().durationMs)
        gate.complete(Unit)
        val result = assertNotNull(pending.await().valueOrNull())
        assertEquals(188_000L, result.tracks.single().durationMs)
        assertEquals(188_000L, result.tracks.single().candidates.single().durationMs)
        assertEquals(1, requests)
        assertEquals(song.id, result.tracks.single().id)
    }

    @Test
    fun optionalDurationTimeoutKeepsResultsAndUsesOneBudget() = runTest {
        val song = track(duration = null)
        val catalog =
            object : CatalogPort {
                override suspend fun searchSongs(query: String) = AppResult.Success(listOf(song))

                override suspend fun searchVideos(query: String) =
                    AppResult.Success(emptyList<Track>())

                override suspend fun findMusicMatches(track: Track) =
                    AppResult.Success(emptyList<Track>())

                override suspend fun durations(
                    sources: List<SourceRef>
                ): AppResult<Map<SourceRef, Long>> {
                    delay(10_000)
                    error("cancelled")
                }
            }
        val result = assertNotNull(SearchCoordinator(catalog).search("Numb").valueOrNull())
        assertEquals(listOf(song), result.tracks)
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test
    fun durationAndFallbackEnrichmentDoNotOverwriteEachOther() = runTest {
        for (durationFirst in listOf(true, false)) {
            val video =
                track("video", title = "Linkin Park - Numb", duration = null, tentative = true)
            val music = track("music", duration = 188_000, album = "Meteora")
            val catalog =
                object : CatalogPort {
                    override suspend fun searchSongs(query: String) =
                        AppResult.Success(emptyList<Track>())

                    override suspend fun searchVideos(query: String) =
                        AppResult.Success(listOf(video))

                    override suspend fun findMusicMatches(track: Track): AppResult<List<Track>> {
                        delay(if (durationFirst) 200 else 100)
                        return AppResult.Success(listOf(music))
                    }

                    override suspend fun durations(
                        sources: List<SourceRef>
                    ): AppResult<Map<SourceRef, Long>> {
                        delay(if (durationFirst) 100 else 200)
                        return AppResult.Success(mapOf(video.candidates.first().source to 188_000L))
                    }
                }
            val result =
                assertNotNull(SearchCoordinator(catalog).search("Numb").valueOrNull())
                    .tracks
                    .single()
            assertEquals("Meteora", result.album?.name)
            assertEquals(188_000L, result.durationMs)
            assertEquals(188_000L, result.candidates.single().durationMs)
            assertEquals(video.id, result.id)
            assertEquals(video.candidates.single().source, result.candidates.single().source)
        }
    }
}
