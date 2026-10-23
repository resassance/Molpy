package app.musicplayer.diagnostics

import app.musicplayer.catalog.*
import app.musicplayer.model.*
import app.musicplayer.network.configureProviders
import com.metrolist.innertubex.InnerTube
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogDiagnosticsTest {
    private val context =
        NetworkContext(7, NetworkConfig(NetworkMode.SYSTEM, null, null), true, true)
    private val ref =
        PublicPlaylistRef("secret-title-sentinel", SourceRef("youtube", "playlist", "PLfixture"))
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")

    private fun fixture(folder: String, name: String) =
        requireNotNull(javaClass.getResource("/catalog/$folder/$name.json")).readText()

    private fun assertSafe(events: List<DiagnosticEvent>) {
        assertTrue(events.all { it.providerVersion == "innertubex-0.10.0" })
        assertTrue(events.all { it.sourceId == null && it.transport == null })
        assertTrue(events.all { it.elapsedMs >= 0 })
        val diagnostics = RedactedDiagnostics()
        events.forEach(diagnostics::record)
        val exported = diagnostics.exportRedacted()
        assertFalse(exported.contains("sentinel"))
        assertFalse(exported.contains("https"))
        assertFalse(exported.contains("page-two"))
        assertFalse(exported.contains("playlist-tail"))
    }

    @Test
    fun typedSearchCountsNormalizedItemsAndCachedContinuationWithoutLeakingQuery() = runTest {
        var calls = 0
        val http =
            HttpClient(
                MockEngine { request ->
                    val tail = request.url.parameters["continuation"] != null
                    val response =
                        if (tail) "continuation" else if (calls == 0) "summary" else "songs"
                    calls++
                    respond(
                        fixture("search", response).replace("page-two", "raw-token-sentinel"),
                        HttpStatusCode.OK,
                        headers,
                    )
                }
            ) {
                configureProviders()
            }
        try {
            val source =
                InnerTubeMusicSearch(InnerTube(http, retryDelay = {}), 7, SessionIdentityStore())
            val events = mutableListOf<DiagnosticEvent>()
            val routed = RoutedMusicSearch({ source }, { context }, events::add)
            val all = routed.search("secret-query-sentinel", SearchFilter.ALL).valueOrNull()!!
            assertIs<SearchItem.ArtistItem>(all.topResult)
            assertEquals(4, all.items().size)
            val tracks = routed.search("secret-query-sentinel", SearchFilter.TRACKS).valueOrNull()!!
            val cursor = tracks.next!!
            assertEquals(
                "song-two",
                routed.more(cursor).valueOrNull()!!.tracks().single().candidates.single().source.id,
            )
            assertEquals(
                "song-two",
                routed.more(cursor).valueOrNull()!!.tracks().single().candidates.single().source.id,
            )
            assertEquals(3, calls)
            assertEquals(listOf("ALL", "TRACKS", "TRACKS", "TRACKS"), events.map { it.filter })
            assertEquals(listOf(4, 1, 1, 1), events.map { it.itemCount })
            assertEquals(listOf(1, 1, 1, 1), events.map { it.pageCount })
            assertEquals(
                listOf(
                    "catalog.search",
                    "catalog.search",
                    "catalog.search.more",
                    "catalog.search.more",
                ),
                events.map { it.operationId },
            )
            assertEquals(
                listOf("initial", "initial", "continuation", "continuation"),
                events.map { it.stage },
            )
            assertTrue(events.all { it.errorCode == null })
            assertSafe(events)
        } finally {
            http.close()
        }
    }

    @Test
    fun publicPlaylistCountsOccurrencesIncludingUnavailableAndReportsFailedPageAndCache() =
        runTest {
            var heads = 0
            var tails = 0
            val http =
                HttpClient(
                    MockEngine { request ->
                        val tail =
                            Json.parseToJsonElement((request.body as TextContent).text)
                                .jsonObject["continuation"] != null
                        val name =
                            if (!tail) {
                                heads++
                                "initial"
                            } else {
                                tails++
                                if (tails == 1) "unknown" else "tail"
                            }
                        respond(
                            fixture("playlists", name)
                                .replace("playlist-tail", "raw-token-sentinel"),
                            HttpStatusCode.OK,
                            headers,
                        )
                    }
                ) {
                    configureProviders()
                }
            try {
                val source =
                    InnerTubeBrowseCatalog(
                        InnerTube(http, retryDelay = {}),
                        7,
                        SessionIdentityStore(),
                    )
                val events = mutableListOf<DiagnosticEvent>()
                val routed = RoutedBrowseCatalog({ source }, { context }, events::add)
                val head = routed.playlist(ref).valueOrNull()!!
                assertEquals(3, head.tracks.items.size)
                assertTrue(head.tracks.items.any { it.unavailable })
                assertEquals(head, routed.playlist(ref).valueOrNull())
                val cursor = head.tracks.next!!
                assertEquals(
                    ErrorCode.PROVIDER_CHANGED,
                    routed.morePlaylist(cursor).errorOrNull()?.code,
                )
                assertEquals(
                    3,
                    routed.morePlaylist(cursor).valueOrNull()!!.items.single().identity.ordinal,
                )
                assertEquals(
                    3,
                    routed.morePlaylist(cursor).valueOrNull()!!.items.single().identity.ordinal,
                )
                assertEquals(1, heads)
                assertEquals(2, tails)
                assertEquals(listOf(3, 3, 0, 1, 1), events.map { it.itemCount })
                assertEquals(listOf(1, 1, 0, 1, 1), events.map { it.pageCount })
                assertEquals(
                    listOf(null, null, ErrorCode.PROVIDER_CHANGED, null, null),
                    events.map { it.errorCode },
                )
                assertEquals(
                    listOf(
                        "catalog.playlist",
                        "catalog.playlist",
                        "catalog.playlist.more",
                        "catalog.playlist.more",
                        "catalog.playlist.more",
                    ),
                    events.map { it.operationId },
                )
                assertEquals(
                    listOf("initial", "initial", "continuation", "continuation", "continuation"),
                    events.map { it.stage },
                )
                assertTrue(events.all { it.filter == "PLAYLISTS" })
                assertSafe(events)
            } finally {
                http.close()
            }
        }

    @Test
    fun unavailableRoutesRetainNetworkFailureAndRecordNoPayloadCounts() = runTest {
        val events = mutableListOf<DiagnosticEvent>()
        val unavailable = context.copy(ready = false)
        val search = RoutedMusicSearch({ null }, { unavailable }, events::add)
        val browse = RoutedBrowseCatalog({ null }, { unavailable }, events::add)
        assertEquals(
            ErrorCode.NETWORK,
            search.search("secret-query-sentinel", SearchFilter.ALBUMS).errorOrNull()?.code,
        )
        assertEquals(ErrorCode.NETWORK, browse.playlist(ref).errorOrNull()?.code)
        assertEquals(listOf("ALBUMS", "PLAYLISTS"), events.map { it.filter })
        assertEquals(listOf(0, 0), events.map { it.itemCount })
        assertEquals(listOf(0, 0), events.map { it.pageCount })
        assertEquals(listOf(ErrorCode.NETWORK, ErrorCode.NETWORK), events.map { it.errorCode })
        assertSafe(events)
    }

    private open class Browse : BrowseCatalog {
        override suspend fun album(ref: AlbumRef): AppResult<AlbumCatalog> =
            error("Unexpected album")

        override suspend fun artist(ref: ArtistRef): AppResult<ArtistCatalog> =
            error("Unexpected artist")

        override suspend fun moreTracks(cursor: CatalogCursor): AppResult<CatalogPage<Track>> =
            error("Unexpected tracks")

        override suspend fun moreAlbums(cursor: CatalogCursor): AppResult<CatalogPage<AlbumCard>> =
            error("Unexpected albums")
    }

    @Test
    fun generationChangesCancelResultsWithoutEmittingStaleDiagnosticEvents() = runTest {
        var network = context
        val searchGate = CompletableDeferred<Unit>()
        val browseGate = CompletableDeferred<Unit>()
        val source =
            object : MusicSearchPort {
                override suspend fun search(
                    query: String,
                    filter: SearchFilter,
                ): AppResult<SearchPage> {
                    withContext(NonCancellable) { searchGate.await() }
                    return AppResult.Success(SearchPage(null, emptyList(), null))
                }

                override suspend fun more(cursor: CatalogCursor): AppResult<SearchPage> =
                    error("Unexpected more")
            }
        val browse =
            object : Browse() {
                override suspend fun playlist(
                    ref: PublicPlaylistRef,
                    refresh: Boolean,
                ): AppResult<PublicPlaylistCatalog> {
                    withContext(NonCancellable) { browseGate.await() }
                    return AppResult.Success(
                        PublicPlaylistCatalog(
                            PublicPlaylistCard(ref, null, null, null),
                            CatalogPage(emptyList(), null),
                        )
                    )
                }
            }
        val events = mutableListOf<DiagnosticEvent>()
        val search = RoutedMusicSearch({ source }, { network }, events::add)
        val playlists = RoutedBrowseCatalog({ browse }, { network }, events::add)
        val first = async { search.search("secret-query-sentinel", SearchFilter.TRACKS) }
        val second = async { playlists.playlist(ref) }
        runCurrent()
        network = context.copy(generation = 8)
        searchGate.complete(Unit)
        browseGate.complete(Unit)
        assertFailsWith<CancellationException> { first.await() }
        assertFailsWith<CancellationException> { second.await() }
        assertTrue(events.isEmpty())
    }
}
