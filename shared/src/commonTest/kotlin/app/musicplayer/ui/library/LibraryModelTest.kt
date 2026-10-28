package app.musicplayer.ui.library

import app.musicplayer.collections.*
import app.musicplayer.model.*
import app.musicplayer.ui.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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

    @Test
    fun oldFavoritesGainDurationWithoutPlayback() = runTest {
        val unknown = unknownDurationTrack()
        val repo =
            MutableCollections().apply {
                changes.value = AppResult.Success(LibraryCollections(favorites = listOf(unknown)))
            }
        val loader = durationMetadata { sources ->
            AppResult.Success(mapOf(sources.single() to 188_000L))
        }
        val player = EngineeringPort()
        val model =
            LibraryModel(repo, player, backgroundScope, metadata = loader).also {
                it.setMetadataVisible(true)
            }
        runCurrent()
        assertEquals(188_000L, model.state.value.data!!.favorites.single().durationMs)
        assertEquals(0, player.plays)
        model.close()
    }

    @Test
    fun failedDurationLookupDoesNotLoopOnCollectionChangesAndCanBeRetried() = runTest {
        val unknown = unknownDurationTrack()
        val initial = LibraryCollections(favorites = listOf(unknown))
        val repo = MutableCollections().apply { changes.value = AppResult.Success(initial) }
        var calls = 0
        val loader = durationMetadata {
            calls++
            AppResult.Failure(AppError(ErrorCode.NETWORK, "fixture.duration", true))
        }
        val model =
            LibraryModel(repo, EngineeringPort(), backgroundScope, metadata = loader).also {
                it.setMetadataVisible(true)
            }
        runCurrent()
        assertEquals(1, calls)
        repo.changes.value = AppResult.Success(initial.copy(artistRevision = 1))
        runCurrent()
        assertEquals(1, calls)
        assertNull(model.state.value.error)
        model.retry()
        runCurrent()
        assertEquals(2, calls)
        model.close()
    }

    @Test
    fun changedPlaybackSourceCanRetryDurationWithoutReopeningLibrary() = runTest {
        val unknown = unknownDurationTrack()
        val initial = LibraryCollections(favorites = listOf(unknown))
        val repo = MutableCollections().apply { changes.value = AppResult.Success(initial) }
        val sources = mutableListOf<SourceRef>()
        val loader = durationMetadata { requested ->
            sources += requested
            if (requested.single().id == "replacement")
                AppResult.Success(mapOf(requested.single() to 188_000L))
            else AppResult.Failure(AppError(ErrorCode.NETWORK, "fixture.duration", true))
        }
        val model =
            LibraryModel(repo, EngineeringPort(), backgroundScope, metadata = loader).also {
                it.setMetadataVisible(true)
            }
        runCurrent()
        val replacement =
            unknown.copy(
                candidates =
                    unknown.candidates.map { it.copy(source = it.source.copy(id = "replacement")) }
            )
        repo.changes.value = AppResult.Success(initial.copy(favorites = listOf(replacement)))
        runCurrent()
        assertEquals(
            listOf(unknown.candidates.first().source, replacement.candidates.first().source),
            sources,
        )
        assertEquals(188_000L, model.state.value.data!!.favorites.single().durationMs)
        model.close()
    }

    @Test
    fun closingLocalPlaylistCollectionCancelsMetadataAndForgetsItsTracks() = runTest {
        val id = PlaylistId("local")
        val missing = unknownDurationTrack()
        val repo =
            MutableCollections().apply {
                playlistChanges.value =
                    AppResult.Success(
                        PlaylistDetail(
                            id,
                            "Local",
                            listOf(PlaylistItem(PlaylistItemId("one"), 0, missing)),
                        )
                    )
            }
        var calls = 0
        var cancelled = false
        val loader = durationMetadata {
            calls++
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val model = LibraryModel(repo, EngineeringPort(), backgroundScope, metadata = loader)
        val collection = backgroundScope.launch { model.playlist(id).collect() }
        runCurrent()
        assertEquals(1, calls)
        collection.cancelAndJoin()
        runCurrent()
        assertTrue(cancelled)
        model.networkInvalidated()
        model.refreshMetadata()
        runCurrent()
        assertEquals(1, calls)
        model.close()
    }
}
