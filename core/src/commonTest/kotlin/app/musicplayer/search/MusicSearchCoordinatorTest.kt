package app.musicplayer.search

import app.musicplayer.catalog.*
import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class MusicSearchCoordinatorTest {
    private val artist =
        SearchItem.ArtistItem(ArtistRef("Linkin Park", SourceRef("youtube", "artist", "lp")), null)

    private fun page(vararg items: SearchItem) =
        SearchPage(null, listOf(SearchSection(SearchFilter.ALL, items.toList())), null)

    private class Raw(var result: AppResult<SearchPage>) : MusicSearchPort {
        var calls = 0

        override suspend fun search(query: String, filter: SearchFilter): AppResult<SearchPage> {
            calls++
            return result
        }

        override suspend fun more(cursor: CatalogCursor) = result
    }

    @Test
    fun trackContinuationUsesSameVersionAndQueryFilterAsFirstPage() = runTest {
        val studio = track("studio")
        val live = track("live", title = "Numb (Live)", version = "live")
        val unrelated = track("other", title = "Somewhere Else")
        fun trackPage(vararg tracks: Track) =
            SearchPage(
                null,
                listOf(
                    SearchSection(
                        SearchFilter.TRACKS,
                        tracks.map {
                            SearchItem.TrackItem(it, it.candidates.single().source)
                        },
                    )
                ),
                CatalogCursor("next"),
            )
        val raw =
            object : MusicSearchPort {
                override suspend fun search(query: String, filter: SearchFilter) =
                    AppResult.Success(trackPage(studio))

                override suspend fun more(cursor: CatalogCursor) =
                    AppResult.Success(trackPage(studio, live, unrelated))
            }
        val coordinator = MusicSearchCoordinator(raw, SearchCoordinator(FakeCatalog()))
        val first = coordinator.search("Linkin Park Numb", SearchFilter.TRACKS).valueOrNull()!!
        val more =
            coordinator
                .more(first.next!!, "Linkin Park Numb", SearchFilter.TRACKS, first.tracks())
                .valueOrNull()!!
        assertEquals(listOf("studio"), more.tracks().map { it.id.value })
        assertNotNull(more.next)
    }

    @Test
    fun allSearchDoesNotFanOutOrBrowse() = runTest {
        val catalog = FakeCatalog()
        val raw = Raw(AppResult.Success(page(artist)))
        val result =
            MusicSearchCoordinator(raw, SearchCoordinator(catalog))
                .search("Linkin Park", SearchFilter.ALL)
                .valueOrNull()!!
        assertEquals(artist, result.sections.single().items.single())
        assertEquals(1, raw.calls)
        assertEquals(0, catalog.songCalls)
        assertEquals(0, catalog.videoCalls)
    }

    @Test
    fun seededTrackFallbackDoesNotRepeatPrimary() = runTest {
        val track = track()
        val catalog = FakeCatalog()
        val raw =
            Raw(
                AppResult.Success(
                    page(SearchItem.TrackItem(track, track.candidates.single().source))
                )
            )
        val result =
            MusicSearchCoordinator(raw, SearchCoordinator(catalog))
                .search("Linkin Park Numb", SearchFilter.TRACKS)
                .valueOrNull()!!
        assertEquals(listOf(track.id), result.tracks().map { it.id })
        assertEquals(0, catalog.songCalls)
        assertEquals(0, catalog.videoCalls)
    }

    @Test
    fun emptyAllUsesExistingMusicThenVideoFallback() = runTest {
        val catalog =
            FakeCatalog().apply { videos = AppResult.Success(listOf(track(tentative = true))) }
        val result =
            MusicSearchCoordinator(Raw(AppResult.Success(page())), SearchCoordinator(catalog))
                .search("Linkin Park Numb", SearchFilter.ALL)
                .valueOrNull()!!
        assertEquals("Numb", result.tracks().single().title)
        assertEquals(1, catalog.songCalls)
        assertEquals(1, catalog.videoCalls)
    }

    @Test
    fun artistFilterNeverFallsBackToVideos() = runTest {
        val catalog = FakeCatalog()
        val result =
            MusicSearchCoordinator(Raw(AppResult.Success(page())), SearchCoordinator(catalog))
                .search("Missing", SearchFilter.ARTISTS)
                .valueOrNull()!!
        assertTrue(result.sections.flatMap { it.items }.isEmpty())
        assertEquals(0, catalog.songCalls + catalog.videoCalls)
    }

    @Test
    fun seededFallbackWithSameTrackIdentityDropsMusicCursor() = runTest {
        val live = track("same", title = "Numb (Live)", version = "live")
        val catalog =
            FakeCatalog().apply {
                videos = AppResult.Success(listOf(track("same", tentative = true)))
            }
        val raw =
            Raw(
                AppResult.Success(
                    page(SearchItem.TrackItem(live, live.candidates.single().source))
                        .copy(next = CatalogCursor("primary-only"))
                )
            )
        val result =
            MusicSearchCoordinator(raw, SearchCoordinator(catalog))
                .search("Linkin Park Numb", SearchFilter.TRACKS)
                .valueOrNull()!!
        assertEquals("Numb", result.tracks().single().title)
        assertNull(result.next)
    }

    @Test
    fun initialRowsArriveBeforeDurationEnrichmentAndKeepTheirOrder() = runTest {
        val gate = CompletableDeferred<Unit>()
        val a = track("a", duration = null)
        val catalog =
            object : CatalogPort {
                override suspend fun searchSongs(query: String) =
                    AppResult.Success(emptyList<Track>())

                override suspend fun searchVideos(query: String) =
                    AppResult.Success(emptyList<Track>())

                override suspend fun findMusicMatches(track: Track) =
                    AppResult.Success(emptyList<Track>())

                override suspend fun durations(
                    sources: List<SourceRef>
                ): AppResult<Map<SourceRef, Long>> {
                    gate.await()
                    return AppResult.Success(sources.associateWith { 187000L })
                }
            }
        val observed = mutableListOf<SearchPage>()
        val coordinator =
            MusicSearchCoordinator(
                Raw(
                    AppResult.Success(
                        page(artist, SearchItem.TrackItem(a, a.candidates.single().source))
                    )
                ),
                SearchCoordinator(catalog),
            )
        val job = async { coordinator.search("Linkin Park", SearchFilter.ALL, observed::add) }
        runCurrent()
        assertEquals(
            listOf(artist.key, "track:youtube/video/a"),
            observed.first().items().map { it.key },
        )
        assertNull(observed.first().tracks().single().durationMs)
        assertFalse(job.isCompleted)
        gate.complete(Unit)
        assertEquals(187000L, job.await().valueOrNull()!!.tracks().single().durationMs)
        assertTrue(
            observed.all {
                it.items().map { item -> item.key } == listOf(artist.key, "track:youtube/video/a")
            }
        )
    }

    @Test
    fun continuationLoadsMissingDurationsWithoutResolvingAudio() = runTest {
        val song = track("continued", duration = null)
        val raw =
            Raw(
                AppResult.Success(page(SearchItem.TrackItem(song, song.candidates.single().source)))
            )
        val catalog =
            object : CatalogPort {
                override suspend fun searchSongs(query: String): AppResult<List<Track>> =
                    error("No search")

                override suspend fun searchVideos(query: String): AppResult<List<Track>> =
                    error("No fallback")

                override suspend fun findMusicMatches(track: Track): AppResult<List<Track>> =
                    error("No matching")

                override suspend fun durations(sources: List<SourceRef>) =
                    AppResult.Success(mapOf(song.candidates.single().source to 188_000L))
            }
        val coordinator = MusicSearchCoordinator(raw, SearchCoordinator(catalog))
        val result =
            coordinator
                .more(CatalogCursor("more"), "Numb", SearchFilter.ALL, emptyList())
                .valueOrNull()!!
        assertEquals(188_000L, result.tracks().single().durationMs)
    }
}
