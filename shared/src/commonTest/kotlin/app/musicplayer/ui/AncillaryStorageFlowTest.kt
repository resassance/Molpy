package app.musicplayer.ui

import app.musicplayer.catalog.*
import app.musicplayer.lyrics.*
import app.musicplayer.model.*
import app.musicplayer.search.MusicSearchCoordinator
import app.musicplayer.search.SearchCoordinator
import app.musicplayer.ui.player.PlayerModel
import app.musicplayer.ui.search.SearchModel
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

/** Exercise the same cached adapters and screen operations used by the production graph. */
@OptIn(ExperimentalCoroutinesApi::class)
class AncillaryStorageFlowTest {
    @Test
    fun lyricsReadFailureSettlesAndExplicitRetryRecoversWithoutPlayback() = runTest {
        lyricsFailure(false)
    }

    @Test
    fun lyricsWriteFailureSettlesAndExplicitRetryRecoversWithoutPlayback() = runTest {
        lyricsFailure(true)
    }

    private suspend fun TestScope.lyricsFailure(write: Boolean) {
        var broken = true
        val cache =
            object : LyricsCache {
                private val delegate = SessionLyricsCache()

                override suspend fun getLyrics(
                    id: TrackId,
                    candidateId: CandidateId,
                    nowMs: Long,
                ): LyricsLookup? {
                    if (broken && !write) throw IllegalStateException(PRIVATE)
                    return delegate.getLyrics(id, candidateId, nowMs)
                }

                override suspend fun saveLyrics(
                    id: TrackId,
                    candidateId: CandidateId,
                    lookup: LyricsLookup,
                    atMs: Long,
                ) {
                    if (broken && write) throw IllegalStateException(PRIVATE)
                    delegate.saveLyrics(id, candidateId, lookup, atMs)
                }
            }
        val seen = mutableListOf<CandidateId>()
        val rig =
            rig(
                cache = cache,
                provider =
                    provider { _, candidate ->
                        seen += candidate.id
                        AppResult.Success(LyricsCandidate(words, true, false))
                    },
            )
        runCurrent()
        runCurrent()
        assertFalse(rig.player.state.value.loadingLyrics)
        database(
            assertIs<LyricsLookup.Failed>(rig.player.state.value.lyrics).error,
            if (write) "lyrics.cache.write" else "lyrics.cache.read",
        )
        rig.assertStable()
        broken = false
        rig.player.loadLyrics()
        runCurrent()
        assertEquals(words, assertIs<LyricsLookup.Available>(rig.player.state.value.lyrics).lyrics)
        assertFalse(rig.player.state.value.loadingLyrics)
        assertTrue(seen.all { it == rig.before.candidateId })
        assertEquals(if (write) 2 else 1, seen.size)
        rig.assertStable()
        rig.close()
    }

    @Test
    fun searchReadFailureSettlesAndExplicitRetryRecoversWithoutPlayback() = runTest {
        searchFailure(false)
    }

    @Test
    fun searchWriteFailureSettlesAndExplicitRetryRecoversWithoutPlayback() = runTest {
        searchFailure(true)
    }

    private suspend fun TestScope.searchFailure(write: Boolean) {
        var broken = true
        val library =
            object : EngineeringLibrary() {
                override suspend fun getCachedSearch(
                    query: String,
                    musicOnly: Boolean,
                    nowMs: Long,
                ): List<Track>? {
                    if (broken && !write) throw IllegalStateException(PRIVATE)
                    return null
                }

                override suspend fun cacheSearch(
                    query: String,
                    musicOnly: Boolean,
                    tracks: List<Track>,
                    atMs: Long,
                ) {
                    if (broken && write) throw IllegalStateException(PRIVATE)
                }
            }
        val rig = rig(library = library)
        runCurrent()
        rig.search.search("Song")
        runCurrent()
        assertFalse(rig.search.state.value.loading)
        database(
            assertNotNull(rig.search.state.value.error),
            if (write) "search.cache.write" else "search.cache.read",
        )
        rig.assertStable()
        broken = false
        rig.search.retry()
        runCurrent()
        assertNull(rig.search.state.value.error)
        assertEquals(listOf(song), rig.search.state.value.page?.tracks())
        assertFalse(rig.search.state.value.loading)
        rig.assertStable()
        rig.close()
    }

    @Test
    fun musicMatchUpsertFailureIsVisibleAndExplicitSearchRetryRecovers() = runTest {
        var broken = true
        val library =
            object : EngineeringLibrary() {
                override suspend fun upsert(track: Track) {
                    if (broken) throw IllegalStateException(PRIVATE)
                    super.upsert(track)
                }
            }
        val catalog =
            Catalog(
                songs = { AppResult.Success(emptyList()) },
                videos = { AppResult.Success(listOf(song)) },
                matches = { AppResult.Success(listOf(song)) },
            )
        val rig = rig(library = library, catalog = catalog)
        runCurrent()
        rig.search.search("Song")
        runCurrent()
        database(assertNotNull(rig.search.state.value.error), "search.matches.write")
        assertFalse(rig.search.state.value.loading)
        rig.assertStable()
        broken = false
        rig.search.retry()
        runCurrent()
        assertNull(rig.search.state.value.error)
        assertEquals(listOf(song), rig.search.state.value.page?.tracks())
        assertEquals(song, library.tracks[song.id])
        rig.assertStable()
        rig.close()
    }

    @Test
    fun unexpectedSearchFailureSettlesAtActualOperationBoundaryAndRetryRecovers() = runTest {
        var broken = true
        val rig =
            rig(
                catalog =
                    Catalog(
                        songs = {
                            if (broken) throw IllegalArgumentException(PRIVATE)
                            AppResult.Success(listOf(song))
                        }
                    )
            )
        runCurrent()
        rig.search.search("Song")
        runCurrent()
        assertEquals(AppError(ErrorCode.UNKNOWN, "search.ui", true), rig.search.state.value.error)
        assertFalse(rig.search.state.value.loading)
        rig.assertStable()
        broken = false
        rig.search.retry()
        runCurrent()
        assertEquals(listOf(song), rig.search.state.value.page?.tracks())
        assertNull(rig.search.state.value.error)
        rig.assertStable()
        rig.close()
    }

    @Test
    fun unexpectedLyricsFailureSettlesAtActualOperationBoundary() = runTest {
        var broken = true
        val library = EngineeringLibrary()
        val port = playingPort()
        val model =
            PlayerModel(
                port,
                library,
                LyricsLoader(
                    listOf(provider { _, _ -> AppResult.Success(null) }),
                    LyricsSelector(),
                    SessionLyricsCache(),
                ) {
                    if (broken) throw IllegalArgumentException(PRIVATE)
                    0L
                },
                backgroundScope,
            )
        runCurrent()
        model.loadLyrics()
        runCurrent()
        assertEquals(
            AppError(ErrorCode.UNKNOWN, "player.lyrics", true),
            model.state.value.lyricsError,
        )
        assertFalse(model.state.value.loadingLyrics)
        broken = false
        model.loadLyrics()
        runCurrent()
        assertEquals(LyricsLookup.Unavailable, model.state.value.lyrics)
        assertEquals(playingPort().snapshot.value, port.snapshot.value)
        model.close()
    }

    @Test
    fun lyricsCacheReadCancellationPropagatesAndCurrentScreenLoadingSettles() = runTest {
        lyricsCancellation(false)
    }

    @Test
    fun lyricsCacheWriteCancellationPropagatesAndCurrentScreenLoadingSettles() = runTest {
        lyricsCancellation(true)
    }

    private suspend fun TestScope.lyricsCancellation(write: Boolean) {
        val cache =
            object : LyricsCache {
                override suspend fun getLyrics(
                    id: TrackId,
                    candidateId: CandidateId,
                    nowMs: Long,
                ): LyricsLookup? {
                    if (!write) throw CancellationException("cancelled")
                    return null
                }

                override suspend fun saveLyrics(
                    id: TrackId,
                    candidateId: CandidateId,
                    lookup: LyricsLookup,
                    atMs: Long,
                ) {
                    throw CancellationException("cancelled")
                }
            }
        val loader =
            LyricsLoader(
                listOf(provider { _, _ -> AppResult.Success(null) }),
                LyricsSelector(),
                cache,
            ) {
                0L
            }
        assertFailsWith<CancellationException> { loader.load(song, song.candidates.first()) }
        val rig = rig(cache = cache)
        runCurrent()
        rig.player.loadLyrics()
        runCurrent()
        assertNull(rig.player.state.value.lyrics)
        assertFalse(rig.player.state.value.loadingLyrics)
        rig.assertStable()
        rig.close()
    }

    @Test
    fun searchCacheReadCancellationPropagatesAndCurrentLoadingSettles() = runTest {
        searchCancellation("read")
    }

    @Test
    fun searchCacheWriteCancellationPropagatesAndCurrentLoadingSettles() = runTest {
        searchCancellation("write")
    }

    @Test
    fun musicMatchUpsertCancellationPropagatesAndCurrentLoadingSettles() = runTest {
        searchCancellation("matches")
    }

    private suspend fun TestScope.searchCancellation(boundary: String) {
        val library =
            object : EngineeringLibrary() {
                override suspend fun getCachedSearch(
                    query: String,
                    musicOnly: Boolean,
                    nowMs: Long,
                ): List<Track>? {
                    if (boundary == "read") throw CancellationException("cancelled")
                    return null
                }

                override suspend fun cacheSearch(
                    query: String,
                    musicOnly: Boolean,
                    tracks: List<Track>,
                    atMs: Long,
                ) {
                    if (boundary == "write") throw CancellationException("cancelled")
                }

                override suspend fun upsert(track: Track) {
                    throw CancellationException("cancelled")
                }
            }
        val rig =
            rig(
                library = library,
                catalog =
                    if (boundary == "matches")
                        Catalog(
                            songs = { AppResult.Success(emptyList()) },
                            videos = { AppResult.Success(listOf(song)) },
                            matches = { AppResult.Success(listOf(song)) },
                        )
                    else Catalog(),
            )
        runCurrent()
        rig.search.search("Song")
        runCurrent()
        assertFalse(rig.search.state.value.loading)
        assertNull(rig.search.state.value.error)
        rig.assertStable()
        rig.close()
    }

    @Test
    fun networkInvalidationCancelsCurrentSearchAndSettlesItsLoading() = runTest {
        val entered = CompletableDeferred<Unit>()
        val rig =
            rig(
                catalog =
                    Catalog(
                        songs = {
                            entered.complete(Unit)
                            awaitCancellation()
                        }
                    )
            )
        runCurrent()
        rig.search.search("Song")
        runCurrent()
        entered.await()
        rig.search.networkInvalidated()
        runCurrent()
        assertFalse(rig.search.state.value.loading)
        assertNull(rig.search.state.value.error)
        rig.assertStable()
        rig.close()
    }

    @Test
    fun staleSearchStorageFailureCannotSettleOrOverwriteNewSearch() = runTest {
        for (fails in listOf(false, true)) {
            val oldGate = CompletableDeferred<Unit>()
            val newGate = CompletableDeferred<Unit>()
            val library =
                object : EngineeringLibrary() {
                    override suspend fun getCachedSearch(
                        query: String,
                        musicOnly: Boolean,
                        nowMs: Long,
                    ): List<Track>? {
                        if (query == "old") {
                            withContext(NonCancellable) { oldGate.await() }
                            if (fails) throw IllegalStateException(PRIVATE)
                        } else newGate.await()
                        return listOf(song)
                    }
                }
            val rig = rig(library = library)
            runCurrent()
            rig.search.search("old")
            runCurrent()
            rig.search.search("Song")
            runCurrent()
            oldGate.complete(Unit)
            runCurrent()
            assertTrue(rig.search.state.value.loading)
            assertNull(rig.search.state.value.error)
            newGate.complete(Unit)
            runCurrent()
            assertEquals("Song", rig.search.query.value)
            assertEquals(listOf(song), rig.search.state.value.page?.tracks())
            rig.assertStable()
            rig.close()
        }
    }

    @Test
    fun staleLyricsStorageFailureCannotSettleOrOverwriteNewCandidateOperation() = runTest {
        for (fails in listOf(false, true)) {
            val oldGate = CompletableDeferred<Unit>()
            val newGate = CompletableDeferred<Unit>()
            val cache =
                object : LyricsCache {
                    override suspend fun getLyrics(
                        id: TrackId,
                        candidateId: CandidateId,
                        nowMs: Long,
                    ): LyricsLookup? {
                        if (candidateId == CandidateId("selected")) {
                            withContext(NonCancellable) { oldGate.await() }
                            if (fails) throw IllegalStateException(PRIVATE)
                        } else newGate.await()
                        return LyricsLookup.Available(words)
                    }

                    override suspend fun saveLyrics(
                        id: TrackId,
                        candidateId: CandidateId,
                        lookup: LyricsLookup,
                        atMs: Long,
                    ) = Unit
                }
            val rig = rig(cache = cache)
            runCurrent()
            rig.player.loadLyrics()
            runCurrent()
            rig.port.snapshot.value = rig.before.copy(candidateId = song.candidates.first().id)
            runCurrent()
            rig.player.loadLyrics()
            runCurrent()
            oldGate.complete(Unit)
            runCurrent()
            assertTrue(rig.player.state.value.loadingLyrics)
            assertNull(rig.player.state.value.lyrics)
            newGate.complete(Unit)
            runCurrent()
            assertEquals(
                words,
                assertIs<LyricsLookup.Available>(rig.player.state.value.lyrics).lyrics,
            )
            assertFalse(rig.player.state.value.loadingLyrics)
            assertTrue(rig.port.selections.isEmpty())
            rig.close()
        }
    }

    @Test
    fun closeSuppressesLateStorageFailure() = runTest {
        val gate = CompletableDeferred<Unit>()
        val library =
            object : EngineeringLibrary() {
                override suspend fun getCachedSearch(
                    query: String,
                    musicOnly: Boolean,
                    nowMs: Long,
                ): List<Track>? {
                    withContext(NonCancellable) { gate.await() }
                    throw IllegalStateException(PRIVATE)
                }
            }
        val rig = rig(library = library)
        runCurrent()
        rig.search.search("Song")
        runCurrent()
        rig.close()
        val closed = rig.search.state.value
        gate.complete(Unit)
        runCurrent()
        assertEquals(closed, rig.search.state.value)
        rig.assertStable()
    }

    private fun TestScope.rig(
        library: EngineeringLibrary = EngineeringLibrary(),
        cache: LyricsCache = SessionLyricsCache(),
        provider: LyricsPort = provider { _, _ -> AppResult.Success(null) },
        catalog: CatalogPort = Catalog(),
    ): Rig {
        val port = playingPort()
        val player =
            PlayerModel(
                port,
                library,
                LyricsLoader(listOf(provider), LyricsSelector(), cache) { 0L },
                backgroundScope,
            )
        val search =
            SearchModel(
                MusicSearchCoordinator(
                    EmptyPrimaryMusicSearch,
                    SearchCoordinator(CachedCatalog(catalog, library) { 0L }),
                ),
                port,
                backgroundScope,
            )
        return Rig(player, search, port, port.snapshot.value, port.queue.value)
    }

    private data class Rig(
        val player: PlayerModel,
        val search: SearchModel,
        val port: EngineeringPort,
        val before: PlaybackSnapshot,
        val queue: QueueSnapshot,
    ) {
        fun close() {
            player.close()
            search.close()
        }

        fun assertStable() {
            assertEquals(before, port.snapshot.value)
            assertEquals(before, player.state.value.snapshot)
            assertEquals(before.track, player.state.value.currentTrack)
            assertEquals(queue, port.queue.value)
            assertEquals(queue, player.state.value.queue)
            assertTrue(port.selections.isEmpty())
            assertTrue(port.commands.isEmpty())
            assertEquals(0, port.plays)
        }
    }

    private fun database(error: AppError, operation: String) {
        assertEquals(AppError(ErrorCode.DATABASE, operation, true), error)
        assertEquals(ErrorAction.RETRY, present(error).primaryAction)
        assertFalse(PRIVATE in error.toString())
        assertFalse(PRIVATE in present(error).message)
    }

    private fun playingPort() =
        EngineeringPort().apply {
            val entry = QueueEntry(QueueEntryId("playing"), song.id)
            queue.value = QueueSnapshot(listOf(entry), entry.id, 1234, true)
            snapshot.value =
                PlaybackSnapshot(
                    PlayerStatus.PLAYING,
                    entry,
                    song,
                    1234,
                    60000,
                    true,
                    candidateId = CandidateId("selected"),
                )
        }

    private class Catalog(
        val songs: suspend (String) -> AppResult<List<Track>> = { AppResult.Success(listOf(song)) },
        val videos: suspend (String) -> AppResult<List<Track>> = { AppResult.Success(emptyList()) },
        val matches: suspend (Track) -> AppResult<List<Track>> = { AppResult.Success(emptyList()) },
    ) : CatalogPort {
        override suspend fun searchSongs(query: String) = songs(query)

        override suspend fun searchVideos(query: String) = videos(query)

        override suspend fun findMusicMatches(track: Track) = matches(track)
    }

    private companion object {
        const val PRIVATE = "private database URI password details"
        val words =
            NormalizedLyrics(
                "words",
                emptyList(),
                LyricsKind.PLAIN,
                SourceRef("fixture", "lyrics", "words"),
            )
        val song =
            sampleTrack("Song").let { base ->
                base.copy(
                    artists = listOf(ArtistRef("Artist", null)),
                    candidates =
                        base.candidates +
                            base.candidates.first().copy(id = CandidateId("selected")),
                )
            }

        fun provider(fetch: suspend (Track, PlaybackCandidate) -> AppResult<LyricsCandidate?>) =
            object : LyricsPort {
                override suspend fun fetch(track: Track, candidate: PlaybackCandidate) =
                    fetch.invoke(track, candidate)
            }
    }
}
