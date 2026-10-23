package app.musicplayer.storage

import app.musicplayer.artists.*
import app.musicplayer.catalog.*
import app.musicplayer.listening.*
import app.musicplayer.model.*
import app.musicplayer.playback.*
import app.musicplayer.queue.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class PlaylistQueueWorkflowTest {
    @Test
    fun playlistPipelineKeepsManualQueueAcrossRestartAndNetworkChange() = runBlocking {
        val library = TestLibrary()
        var cleanup: Harness? = null
        try {
            library.open()
            val h = Harness(library).also { cleanup = it }
            h.persistence.initialize()
            val a = fixture("a")
            library.repository.upsert(a)
            library.repository.setFavorite(a.id, true)
            assertIs<AppResult.Success<Unit>>(
                RoomListeningRepository(library.db) { 0 }
                    .save(
                        ListeningCheckpoint(
                            "before-playlist",
                            1,
                            QueueEntryId("listened"),
                            a,
                            0,
                            12345,
                            12345,
                        )
                    )
            )
            val artists = RoomArtistRepository(library.db) { 0 }
            assertIs<AppResult.Success<*>>(
                artists.saveOverride(a, listOf(ArtistChoice.Name("Мой исполнитель")), null)
            )
            val overrides =
                assertIs<AppResult.Success<ArtistDirectory>>(artists.directory.first())
                    .value
                    .overrides
            val gate = CompletableDeferred<Unit>()
            h.catalog.read = {
                withContext(NonCancellable) { gate.await() }
                AppResult.Success(h.page(3, listOf("old")))
            }
            assertIs<AppResult.Success<Unit>>(
                h.loader.play(
                    PlaylistPlaybackRequest(
                        h.ref,
                        h.page(0, listOf("a", "a", "b"), "tail").items,
                        1,
                        CatalogCursor("tail"),
                    )
                )
            )
            withTimeout(5000) { h.queue.state.first { it.source?.acceptedRows?.size == 3 } }
            h.commands.enqueue(fixture("n1"), QueueInsertion.NEXT)
            h.commands.enqueue(fixture("n2"), QueueInsertion.NEXT)
            h.commands.enqueue(fixture("end"), QueueInsertion.END)
            h.commands.dispatch(QueueCommand.ToggleShuffle)
            val b = h.queue.state.value.entries.first { it.trackId == TrackId("b") }
            h.commands.dispatch(QueueCommand.Remove(b.id))
            h.queue.pause()
            h.persistence.checkpoint()
            val expected = h.queue.state.value
            assertEquals(
                listOf("n2", "n1", "end"),
                QueueOrder.upcoming(expected).map { it.trackId.value },
            )
            assertTrue(expected.source!!.shuffleRequested)
            h.shutdown()
            gate.complete(Unit)
            library.reopen()
            val restored = Harness(library).also { cleanup = it }
            restored.persistence.initialize()
            assertEquals(0, restored.resolves)
            assertEquals(0, restored.catalog.headCalls)
            assertFalse(restored.queue.state.value.playWhenReady)
            assertEquals(expected.entries, restored.queue.state.value.entries)
            restored.catalog.head = { _, refresh ->
                assertTrue(refresh)
                AppResult.Success(
                    PublicPlaylistCatalog(
                        PublicPlaylistCard(restored.ref, null, null, null),
                        restored.page(0, listOf("a", "a", "b", "c", "d")),
                    )
                )
            }
            restored.loader.resumeIfNeeded()
            restored.queue.play()
            withTimeout(5000) {
                restored.queue.state.first { it.source?.phase == QueueSourcePhase.COMPLETE }
            }
            restored.persistence.checkpoint()
            val final = restored.queue.state.value
            assertEquals(
                expected.entries.map { it.id },
                final.entries
                    .filter { entry -> expected.entries.any { it.id == entry.id } }
                    .map { it.id },
            )
            assertEquals(
                setOf("a", "n1", "n2", "end", "c", "d"),
                final.entries.map { it.trackId.value }.toSet(),
            )
            assertFalse(final.entries.any { it.trackId.value == "b" || it.trackId.value == "old" })
            assertTrue(final.shuffleEnabled)
            assertEquals(
                listOf("n2", "n1"),
                QueueOrder.upcoming(final).take(2).map { it.trackId.value },
            )
            assertEquals(1, restored.resolves)
            assertEquals(listOf(a), library.repository.favorites.first())
            assertEquals(
                overrides,
                assertIs<AppResult.Success<ArtistDirectory>>(
                        RoomArtistRepository(library.db) { 0 }.directory.first()
                    )
                    .value
                    .overrides,
            )
            assertEquals(
                12345L,
                assertIs<AppResult.Success<ListeningProfile>>(
                        RoomListeningRepository(library.db) { 0 }.profile.first()
                    )
                    .value
                    .totalMs,
            )
            assertEquals(final.copy(playWhenReady = false), library.repository.restoreQueue())
        } finally {
            cleanup?.shutdown()
            library.close()
        }
    }

    @Test
    fun changedPublicPlaylistPreservesSavedKnownEntriesAfterDatabaseReopen() = runBlocking {
        val library = TestLibrary()
        var cleanup: Harness? = null
        try {
            library.open()
            val first = Harness(library).also { cleanup = it }
            val tracks = listOf(fixture("a"), fixture("b"))
            val entries = tracks.map { QueueEntry(QueueEntryId("saved-${it.id.value}"), it.id) }
            val saved =
                QueueSnapshot(
                    entries,
                    entries.first().id,
                    source =
                        QueueSourceState(
                            "saved",
                            first.ref,
                            0,
                            first.page(0, listOf("a", "b")).items.map { it.identity },
                        ),
                )
            library.repository.saveQueue(saved, tracks)
            first.shutdown()
            library.reopen()
            val resumed = Harness(library).also { cleanup = it }
            resumed.persistence.initialize()
            resumed.catalog.head = { _, _ ->
                AppResult.Success(
                    PublicPlaylistCatalog(
                        PublicPlaylistCard(resumed.ref, null, null, null),
                        resumed.page(0, listOf("b", "a", "c")),
                    )
                )
            }
            assertIs<AppResult.Failure>(resumed.loader.retry())
            assertEquals(entries, resumed.queue.state.value.entries)
            assertEquals(0, resumed.resolves)
            assertEquals(QueueSourcePhase.CHANGED, resumed.queue.state.value.source!!.phase)
            resumed.persistence.checkpoint()
            assertEquals(entries, library.repository.restoreQueue().entries)
            assertEquals(QueueSourcePhase.CHANGED, library.repository.restoreQueue().source!!.phase)
        } finally {
            cleanup?.shutdown()
            library.close()
        }
    }

    private class Harness(val library: TestLibrary) {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ref = PublicPlaylistRef("Список", SourceRef("youtube", "playlist", "PLworkflow"))
        val catalog = Catalog()
        val engine = Engine()
        var resolves = 0
        val coordinator =
            PlaybackCoordinator(
                engine,
                object : PlaybackResolver {
                    override suspend fun resolve(
                        candidate: PlaybackCandidate,
                        capabilities: PlaybackCapabilities,
                        network: NetworkContext,
                    ): AppResult<ResolvedPlayback> {
                        resolves++
                        return AppResult.Success(
                            ResolvedPlayback(
                                candidate.id,
                                Representation("wav", "audio/wav", "pcm", null, 120000, null),
                                TransportKind.PROGRESSIVE,
                                MediaRequest("https://example.invalid/audio", emptyMap()),
                                null,
                                network.generation,
                            )
                        )
                    }
                },
                { NetworkContext(0, NetworkConfig(NetworkMode.SYSTEM, null, null), true, true) },
                owner,
            )
        val queue = QueueController(engine, coordinator, { library.repository.getTrack(it) })
        val persistence =
            PlaybackPersistence(library.repository, queue, coordinator, owner, { 0 }, { 0 })
        val commands = QueuePlaybackCommands(queue, persistence::remember, persistence::rememberAll)
        val loader =
            QueuePlaylistLoader(
                catalog,
                queue,
                commands,
                persistence::rememberAll,
                persistence::commitSourcePage,
                owner,
            )

        init {
            owner.launch { coordinator.snapshot.collect { queue.onPlayback(it) } }
        }

        fun page(offset: Int, ids: List<String>, next: String? = null) =
            CatalogPage(
                ids.mapIndexed { i, id ->
                    PlaylistOccurrence(
                        PlaylistRowIdentity(
                            offset + i,
                            "row-${offset+i}",
                            SourceRef("youtube", "video", id),
                        ),
                        fixture(id),
                        false,
                    )
                },
                next?.let(::CatalogCursor),
            )

        suspend fun shutdown() {
            loader.close()
            persistence.close()
            owner.cancel()
        }
    }

    private class Catalog : BrowseCatalog {
        var headCalls = 0
        var head: suspend (PublicPlaylistRef, Boolean) -> AppResult<PublicPlaylistCatalog> =
            { _, _ ->
                error("Unexpected head")
            }
        var read: suspend (CatalogCursor) -> AppResult<CatalogPage<PlaylistOccurrence>> = {
            error("Unexpected more")
        }

        override suspend fun playlist(
            ref: PublicPlaylistRef,
            refresh: Boolean,
        ): AppResult<PublicPlaylistCatalog> {
            headCalls++
            return head(ref, refresh)
        }

        override suspend fun morePlaylist(cursor: CatalogCursor) = read(cursor)

        override suspend fun album(ref: AlbumRef): AppResult<AlbumCatalog> =
            error("Unexpected album")

        override suspend fun artist(ref: ArtistRef): AppResult<ArtistCatalog> =
            error("Unexpected artist")

        override suspend fun moreTracks(cursor: CatalogCursor): AppResult<CatalogPage<Track>> =
            error("Unexpected tracks")

        override suspend fun moreAlbums(cursor: CatalogCursor): AppResult<CatalogPage<AlbumCard>> =
            error("Unexpected albums")
    }

    private class Engine : PlayerEngine {
        override val snapshot = MutableStateFlow(PlaybackSnapshot())

        override suspend fun load(
            entry: QueueEntry,
            source: PlayableSource,
            positionMs: Long,
            playWhenReady: Boolean,
        ): AppResult<Unit> {
            snapshot.value =
                PlaybackSnapshot(
                    if (playWhenReady) PlayerStatus.PLAYING else PlayerStatus.PAUSED,
                    entry,
                    source.track,
                    positionMs,
                    120000,
                    playWhenReady,
                    loadId = (source as? PlayableSource.Remote)?.loadId,
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
