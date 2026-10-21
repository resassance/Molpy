package app.musicplayer.ui.library

import app.musicplayer.collections.*
import app.musicplayer.model.*
import app.musicplayer.ui.*
import kotlin.test.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaylistModelTest {
    @Test
    fun duplicateOccurrencesAndLastDataSurvivePresentationError() = runTest {
        val repo = MutableCollections()
        val track = sampleTrack()
        val id = PlaylistId("mix")
        val detail =
            PlaylistDetail(
                id,
                "Mix",
                listOf(
                    PlaylistItem(PlaylistItemId("first"), 0, track),
                    PlaylistItem(PlaylistItemId("second"), 1, track),
                ),
            )
        repo.playlistChanges.value = AppResult.Success(detail)
        val model = LibraryModel(repo, EngineeringPort(), backgroundScope)
        val states = mutableListOf<app.musicplayer.ui.components.LoadState<PlaylistDetail>>()
        val observer = backgroundScope.launch { model.playlist(id).toList(states) }
        runCurrent()
        repo.playlistChanges.value =
            AppResult.Failure(AppError(ErrorCode.DATABASE, "fixture", true))
        runCurrent()
        assertEquals(listOf("first", "second"), states.last().data?.items?.map { it.id.value })
        assertNotNull(states.last().error)
        observer.cancel()
        model.close()
    }
}
