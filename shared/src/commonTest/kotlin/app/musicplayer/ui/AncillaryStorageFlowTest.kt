package app.musicplayer.ui

import app.musicplayer.catalog.*
import app.musicplayer.lyrics.*
import app.musicplayer.model.*
import app.musicplayer.search.SearchCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Exercise the same cached adapters and screen operations used by the production graph. */
@OptIn(ExperimentalCoroutinesApi::class)
class AncillaryStorageFlowTest {
    @Test fun lyricsReadFailureSettlesAndExplicitRetryRecoversWithoutPlayback() = runTest { lyricsFailure(false) }
    @Test fun lyricsWriteFailureSettlesAndExplicitRetryRecoversWithoutPlayback() = runTest { lyricsFailure(true) }

    private suspend fun TestScope.lyricsFailure(write: Boolean) {
        var broken = true
        val cache = object : LyricsCache {
            private val delegate = SessionLyricsCache()
            override suspend fun getLyrics(id: TrackId, candidateId: CandidateId, nowMs: Long): LyricsLookup? {
                if (broken && !write) throw IllegalStateException(PRIVATE)
                return delegate.getLyrics(id, candidateId, nowMs)
            }
            override suspend fun saveLyrics(id: TrackId, candidateId: CandidateId, lookup: LyricsLookup, atMs: Long) {
                if (broken && write) throw IllegalStateException(PRIVATE)
                delegate.saveLyrics(id, candidateId, lookup, atMs)
            }
        }
        val seen = mutableListOf<CandidateId>()
        val rig = rig(cache = cache, provider = provider { _, candidate ->
            seen += candidate.id
            AppResult.Success(LyricsCandidate(words, true, false))
        })
        runCurrent()
        rig.model.loadLyrics(); runCurrent()
        assertFalse(rig.model.state.value.loadingLyrics)
        database(assertIs<LyricsLookup.Failed>(rig.model.state.value.lyrics).error,
            if (write) "lyrics.cache.write" else "lyrics.cache.read")
        rig.assertStable()
        broken = false
        rig.model.loadLyrics(); runCurrent()
        assertEquals(words, assertIs<LyricsLookup.Available>(rig.model.state.value.lyrics).lyrics)
        assertFalse(rig.model.state.value.loadingLyrics)
        assertTrue(seen.all { it == rig.before.candidateId })
        assertEquals(if (write) 2 else 1, seen.size)
        rig.assertStable(); rig.model.close()
    }

    @Test fun searchReadFailureSettlesAndExplicitRetryRecoversWithoutPlayback() = runTest { searchFailure(false) }
    @Test fun searchWriteFailureSettlesAndExplicitRetryRecoversWithoutPlayback() = runTest { searchFailure(true) }

    private suspend fun TestScope.searchFailure(write: Boolean) {
        var broken = true
        val library = object : EngineeringLibrary() {
            override suspend fun getCachedSearch(query: String, musicOnly: Boolean, nowMs: Long): List<Track>? {
                if (broken && !write) throw IllegalStateException(PRIVATE)
                return null
            }
            override suspend fun cacheSearch(query: String, musicOnly: Boolean, tracks: List<Track>, atMs: Long) {
                if (broken && write) throw IllegalStateException(PRIVATE)
            }
        }
        val rig = rig(library = library)
        runCurrent()
        rig.model.search("Song")
        assertFalse(rig.model.state.value.loadingSearch)
        database(assertNotNull(rig.model.state.value.searchError), if (write) "search.cache.write" else "search.cache.read")
        rig.assertStable()
        broken = false
        rig.model.search("Song")
        assertNull(rig.model.state.value.searchError)
        assertEquals(listOf(song), rig.model.state.value.results)
        assertFalse(rig.model.state.value.loadingSearch)
        rig.assertStable(); rig.model.close()
    }

    @Test fun musicMatchUpsertFailureIsVisibleAndExplicitSearchRetryRecovers() = runTest {
        var broken = true
        val library = object : EngineeringLibrary() {
            override suspend fun upsert(track: Track) {
                if (broken) throw IllegalStateException(PRIVATE)
                super.upsert(track)
            }
        }
        val catalog = Catalog(songs = { AppResult.Success(emptyList()) }, videos = { AppResult.Success(listOf(song)) },
            matches = { AppResult.Success(listOf(song)) })
        val rig = rig(library = library, catalog = catalog)
        runCurrent(); rig.model.search("Song")
        database(assertNotNull(rig.model.state.value.searchError), "search.matches.write")
        assertFalse(rig.model.state.value.loadingSearch); rig.assertStable()
        broken = false; rig.model.search("Song")
        assertNull(rig.model.state.value.searchError)
        assertEquals(listOf(song), rig.model.state.value.results)
        assertEquals(song, library.tracks[song.id]); rig.assertStable(); rig.model.close()
    }

    @Test fun unexpectedSearchFailureSettlesAtActualOperationBoundaryAndRetryRecovers() = runTest {
        var broken = true
        val rig = rig(catalog = Catalog(songs = {
            if (broken) throw IllegalArgumentException(PRIVATE)
            AppResult.Success(listOf(song))
        }))
        runCurrent(); rig.model.search("Song")
        assertEquals(AppError(ErrorCode.UNKNOWN, "ui.search", true), rig.model.state.value.searchError)
        assertFalse(rig.model.state.value.loadingSearch); rig.assertStable()
        broken = false; rig.model.search("Song")
        assertEquals(listOf(song), rig.model.state.value.results); assertNull(rig.model.state.value.searchError)
        rig.assertStable(); rig.model.close()
    }

    @Test fun unexpectedLyricsFailureSettlesAtActualOperationBoundary() = runTest {
        var broken = true
        val library = EngineeringLibrary()
        val port = playingPort()
        val model = TechnicalScreenModel(SearchCoordinator(Catalog()), port,
            LyricsLoader(listOf(provider { _, _ -> AppResult.Success(null) }), LyricsSelector(), SessionLyricsCache()) {
                if (broken) throw IllegalArgumentException(PRIVATE)
                0L
            }, library, EngineeringNetwork(library), EngineeringSecrets(), backgroundScope)
        runCurrent(); model.loadLyrics(); runCurrent()
        assertEquals(AppError(ErrorCode.UNKNOWN, "ui.lyrics", true), assertIs<LyricsLookup.Failed>(model.state.value.lyrics).error)
        assertFalse(model.state.value.loadingLyrics)
        broken = false; model.loadLyrics(); runCurrent()
        assertEquals(LyricsLookup.Unavailable, model.state.value.lyrics)
        assertEquals(playingPort().snapshot.value, port.snapshot.value); model.close()
    }

    @Test fun lyricsCacheReadCancellationPropagatesAndCurrentScreenLoadingSettles() = runTest { lyricsCancellation(false) }
    @Test fun lyricsCacheWriteCancellationPropagatesAndCurrentScreenLoadingSettles() = runTest { lyricsCancellation(true) }
    private suspend fun TestScope.lyricsCancellation(write: Boolean) {
        val cache = object : LyricsCache {
            override suspend fun getLyrics(id: TrackId, candidateId: CandidateId, nowMs: Long): LyricsLookup? {
                if (!write) throw CancellationException("cancelled")
                return null
            }
            override suspend fun saveLyrics(id: TrackId, candidateId: CandidateId, lookup: LyricsLookup, atMs: Long) {
                throw CancellationException("cancelled")
            }
        }
        val loader = LyricsLoader(listOf(provider { _, _ -> AppResult.Success(null) }), LyricsSelector(), cache) { 0L }
        assertFailsWith<CancellationException> { loader.load(song, song.candidates.first()) }
        val rig = rig(cache = cache)
        runCurrent(); rig.model.loadLyrics(); runCurrent()
        assertNull(rig.model.state.value.lyrics); assertFalse(rig.model.state.value.loadingLyrics)
        rig.assertStable(); rig.model.close()
    }

    @Test fun searchCacheReadCancellationPropagatesAndCurrentLoadingSettles() = runTest { searchCancellation("read") }
    @Test fun searchCacheWriteCancellationPropagatesAndCurrentLoadingSettles() = runTest { searchCancellation("write") }
    @Test fun musicMatchUpsertCancellationPropagatesAndCurrentLoadingSettles() = runTest { searchCancellation("matches") }
    private suspend fun TestScope.searchCancellation(boundary: String) {
        val library = object : EngineeringLibrary() {
            override suspend fun getCachedSearch(query: String, musicOnly: Boolean, nowMs: Long): List<Track>? {
                if (boundary == "read") throw CancellationException("cancelled")
                return null
            }
            override suspend fun cacheSearch(query: String, musicOnly: Boolean, tracks: List<Track>, atMs: Long) {
                if (boundary == "write") throw CancellationException("cancelled")
            }
            override suspend fun upsert(track: Track) { throw CancellationException("cancelled") }
        }
        val rig = rig(library = library, catalog = if (boundary == "matches") Catalog(
            songs = { AppResult.Success(emptyList()) }, videos = { AppResult.Success(listOf(song)) },
            matches = { AppResult.Success(listOf(song)) }) else Catalog())
        runCurrent()
        assertFailsWith<CancellationException> { rig.model.search("Song") }
        assertFalse(rig.model.state.value.loadingSearch); assertNull(rig.model.state.value.searchError)
        rig.assertStable(); rig.model.close()
    }

    @Test fun callerCancellationCancelsCurrentSearchAndSettlesItsLoading() = runTest {
        val entered = CompletableDeferred<Unit>()
        val rig = rig(catalog = Catalog(songs = { entered.complete(Unit); awaitCancellation() }))
        runCurrent()
        val caller = launch { rig.model.search("Song") }
        runCurrent(); entered.await(); caller.cancelAndJoin(); runCurrent()
        assertFalse(rig.model.state.value.loadingSearch); assertNull(rig.model.state.value.searchError)
        rig.assertStable(); rig.model.close()
    }

    @Test fun staleSearchStorageFailureCannotSettleOrOverwriteNewSearch() = runTest {
        for (fails in listOf(false, true)) {
            val oldGate = CompletableDeferred<Unit>(); val newGate = CompletableDeferred<Unit>()
            val library = object : EngineeringLibrary() {
                override suspend fun getCachedSearch(query: String, musicOnly: Boolean, nowMs: Long): List<Track>? {
                    if (query == "old") {
                        withContext(NonCancellable) { oldGate.await() }
                        if (fails) throw IllegalStateException(PRIVATE)
                    } else newGate.await()
                    return listOf(song)
                }
            }
            val rig = rig(library = library); runCurrent()
            val old = launch { rig.model.search("old") }; runCurrent()
            val fresh = launch { rig.model.search("Song") }; runCurrent()
            oldGate.complete(Unit); runCurrent(); old.join()
            assertTrue(rig.model.state.value.loadingSearch); assertNull(rig.model.state.value.searchError)
            newGate.complete(Unit); fresh.join()
            assertEquals("Song", rig.model.state.value.query)
            assertEquals(listOf(song), rig.model.state.value.results); rig.assertStable(); rig.model.close()
        }
    }

    @Test fun staleLyricsStorageFailureCannotSettleOrOverwriteNewCandidateOperation() = runTest {
        for (fails in listOf(false, true)) {
            val oldGate = CompletableDeferred<Unit>(); val newGate = CompletableDeferred<Unit>()
            val cache = object : LyricsCache {
                override suspend fun getLyrics(id: TrackId, candidateId: CandidateId, nowMs: Long): LyricsLookup? {
                    if (candidateId == CandidateId("selected")) {
                        withContext(NonCancellable) { oldGate.await() }
                        if (fails) throw IllegalStateException(PRIVATE)
                    } else newGate.await()
                    return LyricsLookup.Available(words)
                }
                override suspend fun saveLyrics(id: TrackId, candidateId: CandidateId, lookup: LyricsLookup, atMs: Long) = Unit
            }
            val rig = rig(cache = cache); runCurrent(); rig.model.loadLyrics(); runCurrent()
            rig.port.snapshot.value = rig.before.copy(candidateId = song.candidates.first().id)
            runCurrent(); rig.model.loadLyrics(); runCurrent()
            oldGate.complete(Unit); runCurrent()
            assertTrue(rig.model.state.value.loadingLyrics); assertNull(rig.model.state.value.lyrics)
            newGate.complete(Unit); runCurrent()
            assertEquals(words, assertIs<LyricsLookup.Available>(rig.model.state.value.lyrics).lyrics)
            assertFalse(rig.model.state.value.loadingLyrics); assertTrue(rig.port.selections.isEmpty()); rig.model.close()
        }
    }

    @Test fun closeSuppressesLateStorageFailure() = runTest {
        val gate = CompletableDeferred<Unit>()
        val library = object : EngineeringLibrary() {
            override suspend fun getCachedSearch(query: String, musicOnly: Boolean, nowMs: Long): List<Track>? {
                withContext(NonCancellable) { gate.await() }; throw IllegalStateException(PRIVATE)
            }
        }
        val rig = rig(library = library); runCurrent()
        val search = launch { rig.model.search("Song") }; runCurrent()
        rig.model.close(); val closed = rig.model.state.value
        gate.complete(Unit); search.join(); runCurrent()
        assertEquals(closed, rig.model.state.value); rig.assertStable()
    }

    private fun TestScope.rig(library: EngineeringLibrary = EngineeringLibrary(), cache: LyricsCache = SessionLyricsCache(),
        provider: LyricsPort = provider { _, _ -> AppResult.Success(null) }, catalog: CatalogPort = Catalog()): Rig {
        val port = playingPort()
        val model = TechnicalScreenModel(SearchCoordinator(CachedCatalog(catalog, library) { 0L }), port,
            LyricsLoader(listOf(provider), LyricsSelector(), cache) { 0L }, library, EngineeringNetwork(library), EngineeringSecrets(), backgroundScope)
        return Rig(model, port, port.snapshot.value, port.queue.value)
    }
    private data class Rig(val model: TechnicalScreenModel, val port: EngineeringPort, val before: PlaybackSnapshot, val queue: QueueSnapshot) {
        fun assertStable() {
            assertEquals(before, port.snapshot.value); assertEquals(before, model.state.value.playback)
            assertEquals(before.track, model.state.value.selected); assertEquals(queue, port.queue.value)
            assertEquals(queue, model.state.value.queue); assertTrue(port.selections.isEmpty())
            assertTrue(port.commands.isEmpty()); assertEquals(0, port.plays)
        }
    }
    private fun database(error: AppError, operation: String) {
        assertEquals(AppError(ErrorCode.DATABASE, operation, true), error)
        assertEquals(ErrorAction.RETRY, present(error).primaryAction)
        assertFalse(PRIVATE in error.toString()); assertFalse(PRIVATE in present(error).message)
    }
    private fun playingPort() = EngineeringPort().apply {
        val entry = QueueEntry(QueueEntryId("playing"), song.id)
        queue.value = QueueSnapshot(listOf(entry), entry.id, 1234, true)
        snapshot.value = PlaybackSnapshot(PlayerStatus.PLAYING, entry, song, 1234, 60000, true, candidateId = CandidateId("selected"))
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
        val words = NormalizedLyrics("words", emptyList(), LyricsKind.PLAIN, SourceRef("fixture", "lyrics", "words"))
        val song = sampleTrack("Song").let { base -> base.copy(artists = listOf(ArtistRef("Artist", null)),
            candidates = base.candidates + base.candidates.first().copy(id = CandidateId("selected"))) }
        fun provider(fetch: suspend (Track, PlaybackCandidate) -> AppResult<LyricsCandidate?>) = object : LyricsPort {
            override suspend fun fetch(track: Track, candidate: PlaybackCandidate) = fetch.invoke(track, candidate)
        }
    }
}
