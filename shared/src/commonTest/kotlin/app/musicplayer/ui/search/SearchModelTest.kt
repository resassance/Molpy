package app.musicplayer.ui.search

import app.musicplayer.catalog.CatalogPort
import app.musicplayer.model.*
import app.musicplayer.search.SearchCoordinator
import app.musicplayer.ui.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SearchModelTest {
    @Test
    fun latestQueryWinsAndListPlaybackUsesVisibleSnapshot() = runTest {
        val gate = CompletableDeferred<Unit>()
        val a = sampleTrack("A").copy(artists = listOf(ArtistRef("Artist", null)))
        val b = sampleTrack("B").copy(artists = listOf(ArtistRef("Artist", null)))
        val catalog =
            object : CatalogPort {
                override suspend fun searchSongs(query: String): AppResult<List<Track>> {
                    if (query == "A") withContext(NonCancellable) { gate.await() }
                    return AppResult.Success(listOf(if (query == "A") a else b))
                }

                override suspend fun searchVideos(query: String) =
                    AppResult.Success(emptyList<Track>())

                override suspend fun findMusicMatches(track: Track) =
                    AppResult.Success(emptyList<Track>())
            }
        val port = EngineeringPort()
        val model = SearchModel(SearchCoordinator(catalog), port, backgroundScope)
        model.search("A")
        runCurrent()
        model.search("B")
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(b), model.state.value.data)
        model.play(0)
        runCurrent()
        assertEquals(listOf(b.id), port.queue.value.entries.map { it.trackId })
        assertEquals(b, port.snapshot.value.track)
        assertFalse(model.state.value.loading)
        model.close()
    }

    @Test
    fun failureRetainsPriorResultsAndEmptyQueryDoesNotCallProvider() = runTest {
        var fail = false
        var calls = 0
        val catalog =
            object : CatalogPort {
                override suspend fun searchSongs(query: String): AppResult<List<Track>> {
                    calls++
                    return if (fail) AppResult.Failure(AppError(ErrorCode.NETWORK, "fixture", true))
                    else
                        AppResult.Success(
                            listOf(sampleTrack().copy(artists = listOf(ArtistRef("Artist", null))))
                        )
                }

                override suspend fun searchVideos(query: String): AppResult<List<Track>> {
                    calls++
                    return AppResult.Failure(AppError(ErrorCode.NETWORK, "fixture", true))
                }

                override suspend fun findMusicMatches(track: Track) =
                    AppResult.Success(emptyList<Track>())
            }
        val model = SearchModel(SearchCoordinator(catalog), EngineeringPort(), backgroundScope)
        model.search("A")
        runCurrent()
        val previous = model.state.value.data
        assertEquals(1, previous?.size)
        fail = true
        model.search("new")
        runCurrent()
        assertEquals(previous, model.state.value.data)
        assertNotNull(model.state.value.error)
        val before = calls
        model.search(" ")
        runCurrent()
        assertEquals(before, calls)
        assertTrue(model.state.value.data.orEmpty().isEmpty())
        model.close()
    }

    @Test
    fun playbackFailureIsIndependentAndLateFailureCannotReplaceNewCommand() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val port =
            object : EngineeringPort() {
                override suspend fun playList(
                    tracks: List<Track>,
                    startIndex: Int,
                ): AppResult<Unit> {
                    if (++calls == 1) throw IllegalStateException("private failure")
                    if (calls == 2) {
                        withContext(NonCancellable) { gate.await() }
                        return AppResult.Failure(AppError(ErrorCode.NETWORK, "late", true))
                    }
                    return AppResult.Success(Unit)
                }
            }
        val track = sampleTrack().copy(artists = listOf(ArtistRef("Artist", null)))
        val catalog =
            object : CatalogPort {
                override suspend fun searchSongs(query: String) = AppResult.Success(listOf(track))

                override suspend fun searchVideos(query: String) =
                    AppResult.Success(emptyList<Track>())

                override suspend fun findMusicMatches(track: Track) =
                    AppResult.Success(emptyList<Track>())
            }
        val model = SearchModel(SearchCoordinator(catalog), port, backgroundScope)
        model.search("A")
        runCurrent()
        assertEquals(listOf(track), model.state.value.data)
        model.play(0)
        runCurrent()
        assertEquals(ErrorCode.UNKNOWN, model.commandError.value?.code)
        assertEquals(listOf(track), model.state.value.data)
        model.play(0)
        runCurrent()
        model.play(0)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertNull(model.commandError.value)
        assertNull(model.state.value.error)
        model.close()
    }
}
