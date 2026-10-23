package app.musicplayer.storage

import app.musicplayer.artists.*
import app.musicplayer.listening.ListeningProfile
import app.musicplayer.model.*
import app.musicplayer.playback.*
import app.musicplayer.queue.*
import kotlin.random.Random
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class QueueWorkflowTest {
    @Test
    fun queueWorkflowSurvivesDatabaseRestartWithoutChangingLibraryOrArtists() = runBlocking {
        val library = TestLibrary()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            library.open()
            val tracks = listOf("a", "b", "c", "x", "y", "z").map { fixture(it) }
            tracks.forEach { library.repository.upsert(it) }
            val artists = RoomArtistRepository(library.db) { 0 }
            assertIs<AppResult.Success<*>>(
                artists.saveOverride(
                    tracks[0],
                    listOf(ArtistChoice.Name("Локальный исполнитель")),
                    null,
                )
            )
            library.repository.setFavorite(tracks[0].id, true)
            val overrides =
                assertIs<AppResult.Success<ArtistDirectory>>(artists.directory.first())
                    .value
                    .overrides
            val engine = Engine()
            val resolver =
                object : PlaybackResolver {
                    override suspend fun resolve(
                        candidate: PlaybackCandidate,
                        capabilities: PlaybackCapabilities,
                        network: NetworkContext,
                    ): AppResult<ResolvedPlayback> =
                        AppResult.Success(
                            ResolvedPlayback(
                                candidate.id,
                                Representation("test", "audio/wav", "pcm", null, 120000, null),
                                TransportKind.PROGRESSIVE,
                                MediaRequest("https://example.invalid/audio", emptyMap()),
                                null,
                                0,
                            )
                        )
                }
            val network =
                NetworkContext(0, NetworkConfig(NetworkMode.SYSTEM, null, null), true, true)
            val coordinator = PlaybackCoordinator(engine, resolver, { network }, owner)
            val queue =
                QueueController(
                    engine,
                    coordinator,
                    { id -> library.repository.getTrack(id) },
                    Random(42),
                )
            owner.launch { coordinator.snapshot.collect { queue.onPlayback(it) } }
            val commands =
                QueuePlaybackCommands(queue) { track ->
                    library.repository.upsert(track)
                    AppResult.Success(Unit)
                }
            commands.playList(listOf(tracks[0], tracks[0], tracks[1], tracks[2]), 1)
            commands.dispatch(QueueCommand.ToggleShuffle)
            commands.enqueue(tracks[3], QueueInsertion.NEXT)
            commands.enqueue(tracks[4], QueueInsertion.NEXT)
            commands.enqueue(tracks[5], QueueInsertion.END)
            val unchangedLoad = engine.snapshot.value.loadId
            assertEquals(1, engine.loads)
            queue.pause()
            queue.seek(2000)
            assertEquals(unchangedLoad, engine.snapshot.value.loadId)
            commands.dispatch(QueueCommand.Next)
            assertEquals(tracks[4].id, queue.snapshot.value.track?.id)
            commands.dispatch(QueueCommand.Previous)
            assertEquals(tracks[0].id, queue.snapshot.value.track?.id)
            commands.dispatch(QueueCommand.Next)
            assertEquals(tracks[4].id, queue.snapshot.value.track?.id)
            commands.dispatch(QueueCommand.SetShuffle(false))
            assertEquals(
                listOf("x", "b", "c", "z"),
                QueueOrder.upcoming(queue.state.value).map { it.trackId.value },
            )
            queue.seek(1234)
            val expected = queue.state.value
            library.repository.saveQueue(expected)
            library.reopen()
            val restored = library.repository.restoreQueue()
            assertEquals(expected.copy(playWhenReady = false), restored)
            assertEquals(
                overrides,
                assertIs<AppResult.Success<ArtistDirectory>>(
                        RoomArtistRepository(library.db) { 0 }.directory.first()
                    )
                    .value
                    .overrides,
            )
            assertEquals(listOf(tracks[0]), library.repository.favorites.first())
            assertEquals(
                0L,
                assertIs<AppResult.Success<ListeningProfile>>(
                        RoomListeningRepository(library.db) { 0 }.profile.first()
                    )
                    .value
                    .totalMs,
            )
            assertEquals(tracks[0], library.repository.getTrack(tracks[0].id))
            val loads = engine.loads
            queue.restore(restored)
            assertEquals(loads, engine.loads)
            assertEquals(1234L, queue.snapshot.value.positionMs)
            assertFalse(queue.snapshot.value.playWhenReady)
            assertEquals(tracks[3].id, QueueOrder.next(queue.state.value)?.trackId)
        } finally {
            owner.cancel()
            library.close()
        }
    }

    private class Engine : PlayerEngine {
        override val snapshot = MutableStateFlow(PlaybackSnapshot())
        var loads = 0

        override suspend fun load(
            entry: QueueEntry,
            source: PlayableSource,
            positionMs: Long,
            playWhenReady: Boolean,
        ): AppResult<Unit> {
            loads++
            val id =
                when (source) {
                    is PlayableSource.Local -> source.loadId
                    is PlayableSource.Remote -> source.loadId
                }
            snapshot.value =
                PlaybackSnapshot(
                    if (playWhenReady) PlayerStatus.PLAYING else PlayerStatus.PAUSED,
                    entry,
                    source.track,
                    positionMs,
                    120000,
                    playWhenReady,
                    loadId = id,
                )
            return AppResult.Success(Unit)
        }

        override fun play() {
            snapshot.value =
                snapshot.value.copy(playWhenReady = true, status = PlayerStatus.PLAYING)
        }

        override fun pause() {
            snapshot.value =
                snapshot.value.copy(playWhenReady = false, status = PlayerStatus.PAUSED)
        }

        override fun seek(positionMs: Long) {
            snapshot.value = snapshot.value.copy(positionMs = positionMs)
        }

        override fun stop() {
            snapshot.value = PlaybackSnapshot()
        }

        override fun release() {}
    }
}
