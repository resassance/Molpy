package app.musicplayer.ui.search

import app.musicplayer.catalog.*
import app.musicplayer.model.*
import app.musicplayer.search.*
import app.musicplayer.ui.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SearchModelTest {
    @Test
    fun queryChangeRejectsLatePlaybackError() = runTest {
        rejectLatePlaybackError { it.search("new") }
    }

    @Test
    fun filterChangeRejectsLatePlaybackError() = runTest {
        rejectLatePlaybackError { it.selectFilter(SearchFilter.ALBUMS) }
    }

    private suspend fun TestScope.rejectLatePlaybackError(change: (SearchModel) -> Unit) {
        val gate = CompletableDeferred<Unit>()
        var playbackFinished = false
        val source =
            SearchFixture().apply {
                initial = { _, filter ->
                    AppResult.Success(page(if (filter == SearchFilter.ALBUMS) album else a))
                }
                player =
                    object : EngineeringPort() {
                        override suspend fun playList(
                            tracks: List<Track>,
                            startIndex: Int,
                        ): AppResult<Unit> {
                            withContext(NonCancellable) { gate.await() }
                            playbackFinished = true
                            return AppResult.Failure(AppError(ErrorCode.NETWORK, "old.play", true))
                        }
                    }
            }
        val model = source.model(backgroundScope)
        model.search("A")
        runCurrent()
        model.play(source.a.key)
        runCurrent()
        change(model)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertTrue(playbackFinished, "Accepted playback must survive a search change")
        assertNull(model.commandError.value, "Old playback failure leaked into new search results")
        model.close()
    }

    @Test
    fun defaultAllUsesOneRequest() = runTest {
        val source = SearchFixture().apply { initial = { _, _ -> AppResult.Success(page(artist)) } }
        val model = source.model(backgroundScope)
        model.search("Artist")
        runCurrent()
        assertEquals(SearchFilter.ALL, model.state.value.filter)
        assertEquals(listOf("Artist" to SearchFilter.ALL), source.calls)
        assertEquals(0, source.trackCalls)
        assertEquals(source.artist, model.state.value.page?.topResult)
        model.selectFilter(SearchFilter.ALL)
        runCurrent()
        assertEquals(1, source.calls.size)
        model.close()
    }

    @Test
    fun filterChangeRejectsOldResults() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source =
            SearchFixture().apply {
                initial = { query, filter ->
                    if (query == "old") withContext(NonCancellable) { gate.await() }
                    AppResult.Success(page(if (filter == SearchFilter.ALBUMS) album else a))
                }
            }
        val model = source.model(backgroundScope)
        model.search("old")
        runCurrent()
        model.search("new")
        model.selectFilter(SearchFilter.ALBUMS)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(SearchFilter.ALBUMS, model.state.value.filter)
        assertEquals("new", model.state.value.query)
        assertEquals(listOf(source.album), model.state.value.page?.items())
        assertTrue(model.state.value.page?.tracks().orEmpty().isEmpty())
        model.close()
    }

    @Test
    fun cacheIsLimitedToCurrentQuery() = runTest {
        val source = SearchFixture()
        val model = source.model(backgroundScope)
        model.search("first")
        runCurrent()
        model.selectFilter(SearchFilter.ARTISTS)
        runCurrent()
        model.selectFilter(SearchFilter.ALL)
        runCurrent()
        assertEquals(2, source.calls.size)
        model.search("second")
        runCurrent()
        model.selectFilter(SearchFilter.ARTISTS)
        runCurrent()
        model.search("first")
        runCurrent()
        assertEquals(
            listOf(
                "first" to SearchFilter.ALL,
                "first" to SearchFilter.ARTISTS,
                "second" to SearchFilter.ALL,
                "second" to SearchFilter.ARTISTS,
                "first" to SearchFilter.ARTISTS,
            ),
            source.calls,
        )
        model.close()
    }

    @Test
    fun playUsesOnlyCurrentVisibleTracks() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source =
            SearchFixture().apply {
                initial = { query, _ ->
                    if (query == "new") gate.await()
                    AppResult.Success(
                        SearchPage(
                            artist,
                            listOf(SearchSection(SearchFilter.ALL, listOf(a, album, b))),
                            null,
                        )
                    )
                }
            }
        val model = source.model(backgroundScope)
        model.search("mixed")
        runCurrent()
        model.play(source.a.key)
        runCurrent()
        assertEquals(
            listOf(TrackId("A"), TrackId("B")),
            source.player.queue.value.entries.map { it.trackId },
        )
        assertEquals(TrackId("A"), source.player.snapshot.value.track?.id)
        val before = source.player.selections.size
        model.search("new")
        model.play(source.a.key)
        runCurrent()
        assertNull(model.state.value.page)
        assertEquals(before, source.player.selections.size)
        gate.complete(Unit)
        runCurrent()
        model.play(source.artist.key)
        runCurrent()
        assertEquals(before, source.player.selections.size)
        model.close()
    }

    @Test
    fun retryRetainsCurrentDataButNewQueryDoesNot() = runTest {
        val source = SearchFixture()
        val model = source.model(backgroundScope)
        model.search("first")
        runCurrent()
        val previous = model.state.value.page
        source.initial = { _, _ -> AppResult.Failure(AppError(ErrorCode.NETWORK, "fixture", true)) }
        source.trackFailure = AppError(ErrorCode.NETWORK, "fixture", true)
        model.retry()
        runCurrent()
        assertEquals(previous, model.state.value.page)
        assertNotNull(model.state.value.error)
        model.search("new")
        runCurrent()
        assertNull(model.state.value.page)
        assertNotNull(model.state.value.error)
        val before = source.calls.size
        model.search(" ")
        runCurrent()
        assertEquals(before, source.calls.size)
        assertNull(model.state.value.page)
        assertFalse(model.state.value.loading)
        model.close()
    }

    @Test
    fun continuationIsSingleFlightAndRetryKeepsRows() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source =
            SearchFixture().apply {
                initial = { _, _ ->
                    AppResult.Success(page(artist).copy(next = CatalogCursor("one")))
                }
                continuation = {
                    gate.await()
                    AppResult.Failure(AppError(ErrorCode.NETWORK, "fixture.more", true))
                }
            }
        val model = source.model(backgroundScope)
        model.search("first")
        runCurrent()
        model.more()
        model.more()
        runCurrent()
        assertTrue(model.state.value.loadingMore)
        assertFalse(model.state.value.loading)
        assertEquals(1, source.moreCalls)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(source.artist), model.state.value.page?.items())
        assertNotNull(model.state.value.moreError)
        source.continuation = {
            AppResult.Success(
                SearchPage(
                    source.artist,
                    listOf(SearchSection(SearchFilter.ALBUMS, listOf(source.album))),
                    null,
                )
            )
        }
        model.retryMore()
        runCurrent()
        assertEquals(2, source.moreCalls)
        assertEquals(listOf(source.artist, source.album), model.state.value.page?.items())
        assertNull(model.state.value.moreError)
        assertNull(model.state.value.page?.next)
        model.close()
    }

    @Test
    fun networkInvalidationRejectsLatePageAndDropsCursors() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source =
            SearchFixture().apply {
                initial = { _, _ ->
                    AppResult.Success(page(artist).copy(next = CatalogCursor("one")))
                }
                continuation = {
                    withContext(NonCancellable) { gate.await() }
                    AppResult.Success(page(album))
                }
            }
        val model = source.model(backgroundScope)
        model.search("first")
        runCurrent()
        model.more()
        runCurrent()
        model.networkInvalidated()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(source.artist), model.state.value.page?.items())
        assertNull(model.state.value.page?.next)
        assertFalse(model.state.value.loadingMore)
        model.close()
    }

    @Test
    fun enrichmentDoesNotMoveRowsOrDiscardMore() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source =
            SearchFixture().apply {
                a = a.copy(track = a.track.copy(durationMs = null))
                initial = { _, _ ->
                    AppResult.Success(
                        SearchPage(
                            artist,
                            listOf(SearchSection(SearchFilter.TRACKS, listOf(a))),
                            CatalogCursor("one"),
                        )
                    )
                }
                continuation = { AppResult.Success(page(b)) }
                durations = {
                    gate.await()
                    AppResult.Success(mapOf(a.source to 123000L))
                }
            }
        val model = source.model(backgroundScope)
        model.search("mixed")
        runCurrent()
        val keys = model.state.value.page!!.items().map { it.key }
        assertFalse(model.state.value.loading)
        model.updateScroll(2, 31)
        model.more()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(keys + source.b.key, model.state.value.page?.items()?.map { it.key })
        assertEquals(123000L, model.state.value.page?.tracks()?.first()?.durationMs)
        assertEquals(SearchScrollPosition(2, 31), model.state.value.scroll)
        assertNull(model.state.value.page?.next)
        model.close()
    }

    @Test
    fun playbackFailureIsIndependentAndLateFailureCannotReplaceNewCommand() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val source =
            SearchFixture().apply {
                initial = { _, _ -> AppResult.Success(page(a)) }
                player =
                    object : EngineeringPort() {
                        override suspend fun playList(
                            tracks: List<Track>,
                            startIndex: Int,
                        ): AppResult<Unit> {
                            if (++calls == 1) throw IllegalStateException("private failure")
                            if (calls == 2) {
                                withContext(NonCancellable) { gate.await() }
                                return AppResult.Failure(AppError(ErrorCode.NETWORK, "late", true))
                            }
                            return AppResult.Success(Unit)
                        }
                    }
            }
        val model = source.model(backgroundScope)
        model.search("A")
        runCurrent()
        model.play(source.a.key)
        runCurrent()
        assertEquals(ErrorCode.UNKNOWN, model.commandError.value?.code)
        assertNull(model.state.value.error)
        model.play(source.a.key)
        runCurrent()
        model.play(source.a.key)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertNull(model.commandError.value)
        model.close()
    }

    @Test
    fun fallbackTracksCanPlayWhileOptionalMetadataIsStillLoading() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source =
            SearchFixture().apply {
                initial = { _, _ -> AppResult.Success(SearchPage(null, emptyList(), null)) }
                fallback =
                    sampleTrack("Artist - Song [Official Video]")
                        .copy(
                            provenance = sampleTrack().provenance.copy(tentative = true),
                            artists = listOf(ArtistRef("Uploader", null)),
                        )
                matches = {
                    gate.await()
                    AppResult.Success(emptyList())
                }
            }
        val model = source.model(backgroundScope)
        model.search("Song")
        runCurrent()
        val shown = model.state.value.page!!.tracks().single()
        assertEquals("Song", shown.title)
        assertEquals("Artist", shown.artists.single().name)
        assertFalse(model.state.value.loading)
        model.play(model.state.value.page!!.items().single().key)
        runCurrent()
        assertEquals(shown, source.player.snapshot.value.track)
        gate.complete(Unit)
        runCurrent()
        model.close()
    }
}

internal fun page(top: SearchItem) = SearchPage(top, emptyList(), null)

internal class SearchFixture : MusicSearchPort {
    val artist =
        SearchItem.ArtistItem(ArtistRef("Artist", SourceRef("test", "artist", "artist")), null)
    val album =
        SearchItem.AlbumItem(
            AlbumCard(
                AlbumRef("Album", SourceRef("test", "album", "album")),
                listOf(artist.ref),
                Artwork(ArtworkKind.PLACEHOLDER, null, null),
            )
        )
    var a = item("A")
    val b = item("B")
    var player = EngineeringPort()
    val calls = mutableListOf<Pair<String, SearchFilter>>()
    var moreCalls = 0
    var trackCalls = 0
    var initial: suspend (String, SearchFilter) -> AppResult<SearchPage> = { _, filter ->
        AppResult.Success(page(if (filter == SearchFilter.ALBUMS) album else artist))
    }
    var continuation: suspend (CatalogCursor) -> AppResult<SearchPage> = {
        AppResult.Success(page(album))
    }
    var durations: suspend () -> AppResult<Map<SourceRef, Long>> = { AppResult.Success(emptyMap()) }
    var matches: suspend () -> AppResult<List<Track>> = { AppResult.Success(emptyList()) }
    var fallback: Track? = null
    var trackFailure: AppError? = null

    override suspend fun search(query: String, filter: SearchFilter): AppResult<SearchPage> {
        calls += query to filter
        return initial(query, filter)
    }

    override suspend fun more(cursor: CatalogCursor): AppResult<SearchPage> {
        moreCalls++
        return continuation(cursor)
    }

    fun model(scope: CoroutineScope): SearchModel =
        SearchModel(
            MusicSearchCoordinator(
                this,
                SearchCoordinator(
                    object : CatalogPort {
                        override suspend fun searchSongs(query: String): AppResult<List<Track>> {
                            trackCalls++
                            return trackFailure?.let { AppResult.Failure(it) }
                                ?: AppResult.Success(emptyList())
                        }

                        override suspend fun searchVideos(query: String) =
                            trackFailure?.let { AppResult.Failure(it) }
                                ?: AppResult.Success(listOfNotNull(fallback))

                        override suspend fun findMusicMatches(track: Track) = matches()

                        override suspend fun durations(sources: List<SourceRef>) =
                            this@SearchFixture.durations()
                    }
                ),
            ),
            player,
            scope,
        )

    private fun item(title: String): SearchItem.TrackItem {
        val track = sampleTrack(title).copy(artists = listOf(artist.ref))
        return SearchItem.TrackItem(track, track.candidates.single().source)
    }
}
