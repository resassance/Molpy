package app.musicplayer.ui

import app.musicplayer.catalog.CatalogPort
import app.musicplayer.lyrics.*
import app.musicplayer.model.*
import app.musicplayer.playback.PlaybackPort
import app.musicplayer.search.SearchCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TechnicalFlowTest {
    @Test fun ignoresStaleSearch() = runTest {
        val gate = CompletableDeferred<Unit>()
        val catalog = Catalog { query ->
            if (query == "A") withContext(NonCancellable) { gate.await() }
            AppResult.Success(listOf(track(query)))
        }
        val model = model(catalog, Port(), backgroundScope)
        val first = launch { model.search("A") }
        runCurrent()
        model.search("B")
        gate.complete(Unit)
        first.join()
        assertEquals(listOf(track("B")), model.state.value.results)
        assertEquals("B", model.state.value.query)
        assertFalse(model.state.value.loadingSearch)
        model.close()
    }

    @Test fun showsMetadataWhenPlaybackFails() = runTest {
        val port = Port().apply { failure = AppError(ErrorCode.URL_REJECTED, "test.select", false) }
        val model = model(Catalog(), port, backgroundScope)
        val selected = track("A")
        model.select(selected)
        runCurrent()
        assertEquals(selected, model.state.value.selected)
        assertEquals(selected, model.state.value.playback.track)
        assertEquals(ErrorCode.URL_REJECTED, model.state.value.playback.error?.code)
        model.close()
    }

    @Test fun lyricsFailureDoesNotStopAudio() = runTest {
        val port = Port()
        val model = model(Catalog(), port, backgroundScope, lyricsPort { _, _ ->
            AppResult.Failure(AppError(ErrorCode.NETWORK, "test.lyrics", true))
        })
        model.select(track("A"))
        runCurrent()
        model.loadLyrics()
        runCurrent()
        assertIs<LyricsLookup.Failed>(model.state.value.lyrics)
        assertEquals(PlayerStatus.PLAYING, model.state.value.playback.status)
        assertEquals(0, port.stops)
        model.close()
    }

    @Test fun lateLyricsStayBoundToTheirSelection() = runTest {
        val gate = CompletableDeferred<Unit>()
        val model = model(Catalog(), Port(), backgroundScope, lyricsPort { _, _ ->
            withContext(NonCancellable) { gate.await() }
            AppResult.Success(LyricsCandidate(NormalizedLyrics("synthetic", emptyList(), LyricsKind.PLAIN,
                SourceRef("test", "lyrics", "a")), true, false))
        })
        model.select(track("A")); runCurrent()
        model.loadLyrics(); runCurrent()
        model.select(track("B")); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(track("B"), model.state.value.selected)
        assertNull(model.state.value.lyrics)
        assertFalse(model.state.value.loadingLyrics)
        model.close()
    }

    @Test fun restoredSnapshotKeepsActualTrackMetadata() = runTest {
        val actual = track("Actual").copy(album = AlbumRef("Actual release", null))
        val port = Port().apply { snapshot.value = PlaybackSnapshot(PlayerStatus.PLAYING,
            QueueEntry(QueueEntryId("restored"), actual.id), actual, 12345, 60000, true) }
        val model = model(Catalog(), port, backgroundScope)
        runCurrent()
        assertEquals(actual, model.state.value.selected)
        assertEquals(12345, model.state.value.playback.positionMs)
        model.close()
    }

    @Test fun closeDoesNotStopServicePlaybackOrCancelOwner() = runTest {
        val port = Port()
        val model = model(Catalog(), port, backgroundScope)
        model.select(track("A")); runCurrent()
        model.close()
        assertEquals(0, port.stops)
        assertEquals(PlayerStatus.PLAYING, port.snapshot.value.status)
        assertTrue(backgroundScope.isActive)
    }

    @Test fun lateSelectionFailureDoesNotReplaceNewTrack() = runTest {
        val gate = CompletableDeferred<Unit>()
        val port = object : Port() {
            override suspend fun select(track: Track, entry: QueueEntry, positionMs: Long, playWhenReady: Boolean): AppResult<Unit> {
                if (track.title == "A") {
                    withContext(NonCancellable) { gate.await() }
                    return AppResult.Failure(AppError(ErrorCode.URL_REJECTED, "test.old", false))
                }
                return super.select(track, entry, positionMs, playWhenReady)
            }
        }
        val model = model(Catalog(), port, backgroundScope)
        model.select(track("A")); runCurrent()
        model.select(track("B")); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(track("B"), model.state.value.selected)
        assertEquals(PlayerStatus.PLAYING, model.state.value.playback.status)
        assertNull(model.state.value.playback.error)
        model.close()
    }

    private fun model(catalog: CatalogPort, port: PlaybackPort, scope: CoroutineScope,
                      provider: LyricsPort = lyricsPort { _, _ -> AppResult.Success(null) }) =
        TechnicalScreenModel(SearchCoordinator(catalog), port,
            LyricsLoader(listOf(provider), LyricsSelector(), SessionLyricsCache()) { 0L }, scope)

    private class Catalog(private val songs: suspend (String) -> AppResult<List<Track>> = { AppResult.Success(emptyList()) }) : CatalogPort {
        override suspend fun searchSongs(query: String) = songs(query)
        override suspend fun searchVideos(query: String) = AppResult.Success(emptyList<Track>())
        override suspend fun findMusicMatches(track: Track) = AppResult.Success(emptyList<Track>())
    }

    private open class Port : PlaybackPort {
        override val snapshot = MutableStateFlow(PlaybackSnapshot())
        var failure: AppError? = null
        var stops = 0
        override suspend fun select(track: Track, entry: QueueEntry, positionMs: Long, playWhenReady: Boolean): AppResult<Unit> {
            failure?.let { return AppResult.Failure(it) }
            snapshot.value = PlaybackSnapshot(PlayerStatus.PLAYING, entry, track, positionMs, track.durationMs, playWhenReady)
            return AppResult.Success(Unit)
        }
        override fun play() { snapshot.value = snapshot.value.copy(status = PlayerStatus.PLAYING, playWhenReady = true) }
        override fun pause() { snapshot.value = snapshot.value.copy(status = PlayerStatus.PAUSED, playWhenReady = false) }
        override fun seek(positionMs: Long) { snapshot.value = snapshot.value.copy(positionMs = positionMs) }
        override fun stop() { stops++ }
    }

    private fun lyricsPort(fetch: suspend (Track, PlaybackCandidate) -> AppResult<LyricsCandidate?>) = object : LyricsPort {
        override suspend fun fetch(track: Track, candidate: PlaybackCandidate) = fetch.invoke(track, candidate)
    }

    private fun track(title: String): Track {
        val source = SourceRef("test", "video", title)
        return Track(TrackId(title), title, listOf(ArtistRef("Artist", null)), null,
            Artwork(ArtworkKind.PLACEHOLDER, null, null), 60000, setOf(source),
            MetadataProvenance(source, 0, false), listOf(PlaybackCandidate(CandidateId(title), source, "", 60000, MatchConfidence.HIGH)))
    }
}
