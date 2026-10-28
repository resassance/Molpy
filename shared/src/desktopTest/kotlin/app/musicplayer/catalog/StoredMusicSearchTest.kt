package app.musicplayer.catalog

import app.musicplayer.model.*
import app.musicplayer.search.*
import app.musicplayer.storage.TestLibrary
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class StoredMusicSearchTest {
    @Test
    fun searchAndContinuationReuseKnownDurationAfterReopeningLibrary() = runBlocking {
        val library = TestLibrary()
        try {
            library.open()
            val known = app.musicplayer.storage.fixture("seen")
            library.repository.upsert(known)
            library.reopen()
            val missing =
                known.copy(
                    durationMs = null,
                    candidates = known.candidates.map { it.copy(durationMs = null) },
                )
            val page =
                SearchPage(
                    null,
                    listOf(
                        SearchSection(
                            SearchFilter.TRACKS,
                            listOf(
                                SearchItem.TrackItem(missing, missing.candidates.single().source)
                            ),
                        )
                    ),
                    null,
                )
            val raw =
                object : MusicSearchPort {
                    override suspend fun search(query: String, filter: SearchFilter) =
                        AppResult.Success(page)

                    override suspend fun more(cursor: CatalogCursor) = AppResult.Success(page)
                }
            val stored = StoredMusicSearch(raw, library.repository)
            assertEquals(
                known.durationMs,
                stored.search("seen", SearchFilter.ALL).valueOrNull()!!.tracks().single().durationMs,
            )
            assertEquals(
                known.durationMs,
                stored.more(CatalogCursor("more")).valueOrNull()!!.tracks().single().durationMs,
            )
        } finally {
            library.close()
        }
    }

    @Test
    fun firstSeenTrackExistsBeforeDurationEnrichmentWritesBack() = runBlocking {
        val library = TestLibrary()
        try {
            library.open()
            val unknown =
                app.musicplayer.storage.fixture("fresh").copy(durationMs = null).let {
                    it.copy(candidates = it.candidates.map { c -> c.copy(durationMs = null) })
                }
            val source = unknown.candidates.single().source
            val canonical = unknown.copy(externalIds = setOf(source))
            val page =
                SearchPage(
                    null,
                    listOf(
                        SearchSection(
                            SearchFilter.TRACKS,
                            listOf(SearchItem.TrackItem(canonical, source)),
                        )
                    ),
                    null,
                )
            val raw =
                object : MusicSearchPort {
                    override suspend fun search(query: String, filter: SearchFilter) =
                        AppResult.Success(page)

                    override suspend fun more(cursor: CatalogCursor) = AppResult.Success(page)
                }
            val catalog =
                object : CatalogPort {
                    override suspend fun searchSongs(query: String) =
                        AppResult.Success(emptyList<Track>())

                    override suspend fun searchVideos(query: String) =
                        AppResult.Success(emptyList<Track>())

                    override suspend fun findMusicMatches(track: Track) =
                        AppResult.Success(emptyList<Track>())

                    override suspend fun durations(sources: List<SourceRef>) =
                        AppResult.Success(mapOf(source to 188_000L))
                }
            val search =
                MusicSearchCoordinator(
                    StoredMusicSearch(raw, library.repository),
                    SearchCoordinator(CachedCatalog(catalog, library.repository) { 0L }),
                )
            val result = assertNotNull(search.search("Same title", SearchFilter.ALL).valueOrNull())
            assertEquals(188_000L, result.tracks().single().durationMs)
            library.reopen()
            assertEquals(
                188_000L,
                assertNotNull(library.repository.getTrack(unknown.id)).durationMs,
            )
        } finally {
            library.close()
        }
    }
}
