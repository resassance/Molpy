package app.musicplayer.ui.library

import app.musicplayer.collections.*
import app.musicplayer.model.*
import app.musicplayer.ui.*
import kotlin.test.*
import kotlinx.coroutines.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryModelTest {
    @Test
    fun shuffleUsesOneServiceCommandAndReportsItsFailure() = runTest {
        val tracks = listOf(sampleTrack("A"), sampleTrack("A"), sampleTrack("B"))
        val requests = mutableListOf<List<Track>>()
        val error = AppError(ErrorCode.SOURCE_UNAVAILABLE, "fixture.shuffle", true)
        val port =
            object : EngineeringPort() {
                override suspend fun playShuffledList(tracks: List<Track>): AppResult<Unit> {
                    requests += tracks
                    return AppResult.Failure(error)
                }
            }
        val model = LibraryModel(MutableCollections(), port, backgroundScope)
        model.playShuffled(emptyList())
        model.playShuffled(tracks)
        runCurrent()
        assertEquals(listOf(tracks), requests)
        assertEquals(error, model.commandError.value)
        assertTrue(port.commands.isEmpty())
        assertEquals(0, port.plays)
        model.close()
    }

    @Test
    fun repositoryFailureKeepsLastCollectionAndNeverChangesAudio() = runTest {
        val repo = MutableCollections()
        val track = sampleTrack()
        repo.changes.value = AppResult.Success(LibraryCollections(favorites = listOf(track)))
        val port = EngineeringPort()
        val model = LibraryModel(repo, port, backgroundScope)
        runCurrent()
        repo.changes.value = AppResult.Failure(AppError(ErrorCode.DATABASE, "fixture", true))
        runCurrent()
        assertEquals(listOf(track), model.state.value.data?.favorites)
        assertEquals(ErrorCode.DATABASE, model.state.value.error?.code)
        assertEquals(0, port.plays)
        assertNull(port.snapshot.value.track)
        model.close()
    }
}
