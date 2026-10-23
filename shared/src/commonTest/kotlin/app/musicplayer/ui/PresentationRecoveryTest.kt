package app.musicplayer.ui

import app.musicplayer.catalog.CatalogPort
import app.musicplayer.collections.*
import app.musicplayer.listening.*
import app.musicplayer.lyrics.*
import app.musicplayer.model.*
import app.musicplayer.search.MusicSearchCoordinator
import app.musicplayer.search.SearchCoordinator
import app.musicplayer.ui.library.LibraryModel
import app.musicplayer.ui.player.PlayerModel
import app.musicplayer.ui.profile.ProfileModel
import app.musicplayer.ui.search.SearchModel
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PresentationRecoveryTest {
    private fun lyrics() = LyricsLoader(emptyList(), LyricsSelector(), SessionLyricsCache()) { 0L }

    @Test
    fun successfulPlaylistCommandCannotHideFailedCollectionObserver() = runTest {
        var attempts = 0
        val track = sampleTrack()
        val repository =
            object : CollectionsRepository by MutableCollections() {
                override val overview =
                    flow<AppResult<LibraryCollections>> {
                        if (++attempts == 1) throw IllegalStateException("private database details")
                        emit(AppResult.Success(LibraryCollections(favorites = listOf(track))))
                    }
            }
        val port = EngineeringPort()
        val model = LibraryModel(repository, port, backgroundScope)
        runCurrent()
        assertEquals(ErrorCode.DATABASE, model.state.value.error?.code)
        model.create("New")
        assertNotNull(model.state.value.error)
        model.retry()
        runCurrent()
        assertEquals(listOf(track), model.state.value.data?.favorites)
        assertNull(model.state.value.error)
        assertEquals(2, attempts)
        assertEquals(0, port.plays)
        model.close()
    }

    @Test
    fun separateProfileFailureSurvivesLibraryRecovery() = runTest {
        val repository =
            MutableCollections().apply {
                changes.value = AppResult.Failure(AppError(ErrorCode.DATABASE, "collections", true))
            }
        val listening =
            object : ListeningRepository {
                override val profile =
                    flow<AppResult<ListeningProfile>> {
                        throw IllegalStateException("private storage")
                    }

                override suspend fun history(cursor: HistoryCursor?, limit: Int) =
                    AppResult.Success(HistoryPage(emptyList(), null))

                override suspend fun save(checkpoint: ListeningCheckpoint) = error("read only")
            }
        val port = EngineeringPort()
        val library = LibraryModel(repository, port, backgroundScope)
        val profile = ProfileModel(listening, port, backgroundScope)
        runCurrent()
        assertNotNull(library.state.value.error)
        assertNotNull(profile.profile.value.error)
        repository.changes.value = AppResult.Success(LibraryCollections())
        runCurrent()
        assertNull(library.state.value.error)
        assertNotNull(profile.profile.value.error)
        assertEquals(0, port.plays)
        library.close()
        profile.close()
    }

    @Test
    fun obsoleteQueueMetadataFailureCannotOverwriteFreshLookup() = runTest {
        val old = sampleTrack("old")
        val fresh = sampleTrack("fresh")
        val gate = CompletableDeferred<Unit>()
        val records =
            object : EngineeringLibrary() {
                override suspend fun getTrack(id: TrackId): Track? {
                    if (id == old.id) {
                        withContext(NonCancellable) { gate.await() }
                        throw IllegalStateException("late private database error")
                    }
                    return fresh
                }
            }
        val port =
            EngineeringPort().apply {
                queue.value = QueueSnapshot(listOf(QueueEntry(QueueEntryId("old"), old.id)))
            }
        val model = PlayerModel(port, records, lyrics(), backgroundScope)
        runCurrent()
        port.queue.value = QueueSnapshot(listOf(QueueEntry(QueueEntryId("fresh"), fresh.id)))
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(mapOf(fresh.id to fresh), model.state.value.queueTracks)
        assertNull(model.state.value.repositoryError)
        assertEquals(0, port.plays)
        model.close()
    }

    @Test
    fun queueLookupRetryPreservesActualPausedSnapshot() = runTest {
        var broken = true
        val track = sampleTrack()
        val entry = QueueEntry(QueueEntryId("paused"), track.id)
        val records =
            object : EngineeringLibrary() {
                override suspend fun getTrack(id: TrackId): Track? {
                    if (broken) throw IllegalStateException("private")
                    return track
                }
            }
        val port =
            EngineeringPort().apply {
                queue.value = QueueSnapshot(listOf(entry), entry.id, 1200, false)
                snapshot.value =
                    PlaybackSnapshot(PlayerStatus.PAUSED, entry, track, 1200, 60000, false)
            }
        val model = PlayerModel(port, records, lyrics(), backgroundScope)
        runCurrent()
        assertNotNull(model.state.value.repositoryError)
        broken = false
        model.retry()
        runCurrent()
        assertNull(model.state.value.repositoryError)
        assertEquals(track, model.state.value.queueTracks[track.id])
        assertEquals(port.snapshot.value, model.state.value.snapshot)
        assertEquals(0, port.plays)
        model.close()
    }

    @Test
    fun networkChangeRejectsLateSearchAndLyricsWithoutChangingAudio() = runTest {
        val gate = CompletableDeferred<Unit>()
        val track = sampleTrack().copy(artists = listOf(ArtistRef("Artist", null)))
        val catalog =
            object : CatalogPort {
                override suspend fun searchSongs(query: String): AppResult<List<Track>> {
                    withContext(NonCancellable) { gate.await() }
                    return AppResult.Success(listOf(track))
                }

                override suspend fun searchVideos(query: String) =
                    AppResult.Success(emptyList<Track>())

                override suspend fun findMusicMatches(track: Track) =
                    AppResult.Success(emptyList<Track>())
            }
        val loader =
            LyricsLoader(
                listOf(
                    object : LyricsPort {
                        override suspend fun fetch(
                            track: Track,
                            candidate: PlaybackCandidate,
                        ): AppResult<LyricsCandidate?> {
                            withContext(NonCancellable) { gate.await() }
                            return AppResult.Success(
                                LyricsCandidate(
                                    NormalizedLyrics(
                                        "late",
                                        emptyList(),
                                        LyricsKind.PLAIN,
                                        SourceRef("fixture", "lyrics", "late"),
                                    ),
                                    true,
                                    false,
                                )
                            )
                        }
                    }
                ),
                LyricsSelector(),
                SessionLyricsCache(),
            ) {
                0L
            }
        val port =
            EngineeringPort().apply {
                snapshot.value =
                    PlaybackSnapshot(
                        PlayerStatus.PLAYING,
                        QueueEntry(QueueEntryId("actual"), track.id),
                        track,
                        1234,
                        60000,
                        true,
                        candidateId = track.candidates.single().id,
                    )
            }
        val before = port.snapshot.value
        val player = PlayerModel(port, EngineeringLibrary(), loader, backgroundScope)
        val search =
            SearchModel(
                MusicSearchCoordinator(EmptyPrimaryMusicSearch, SearchCoordinator(catalog)),
                port,
                backgroundScope,
            )
        search.search("song")
        runCurrent()
        search.networkInvalidated()
        player.networkInvalidated()
        gate.complete(Unit)
        runCurrent()
        assertTrue(search.state.value.page?.tracks().orEmpty().isEmpty())
        assertFalse(search.state.value.loading)
        assertNull(player.state.value.lyrics)
        assertFalse(player.state.value.loadingLyrics)
        assertEquals(before, player.state.value.snapshot)
        assertEquals(before, port.snapshot.value)
        player.close()
        search.close()
    }
}
