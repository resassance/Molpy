package app.musicplayer.ui.library

import app.musicplayer.model.*
import app.musicplayer.ui.*
import app.musicplayer.ui.components.TrackActions
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class TrackActionsTest {
    @Test
    fun metadataFailureLeavesQueueAndFavoritesUntouched() = runTest {
        val library =
            object : EngineeringLibrary() {
                override suspend fun upsert(track: Track) {
                    throw IllegalStateException("private database detail")
                }
            }
        val port =
            object : EngineeringPort() {
                override suspend fun enqueue(
                    track: Track,
                    position: app.musicplayer.queue.QueueInsertion,
                ): AppResult<Unit> =
                    AppResult.Failure(AppError(ErrorCode.DATABASE, "fixture", true))
            }
        val actions = TrackActions(library, MutableCollections(), port, PresentationDownloads())
        assertEquals(ErrorCode.DATABASE, actions.enqueue(sampleTrack()).errorOrNull()?.code)
        assertEquals(ErrorCode.DATABASE, actions.favorite(sampleTrack(), true).errorOrNull()?.code)
        assertTrue(port.queue.value.entries.isEmpty())
        assertTrue(library.favorites.first().isEmpty())
        assertNull(port.snapshot.value.track)
    }
}
