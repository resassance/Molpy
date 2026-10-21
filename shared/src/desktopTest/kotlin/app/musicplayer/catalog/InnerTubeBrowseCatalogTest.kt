package app.musicplayer.catalog

import app.musicplayer.model.*
import app.musicplayer.network.configureProviders
import com.metrolist.innertubex.InnerTube
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class InnerTubeBrowseCatalogTest {
    private val album = AlbumRef("Meteora", SourceRef("youtube", "album", "album-one"))
    private val artist = ArtistRef("Linkin Park", SourceRef("youtube", "artist", "artist-one"))
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")

    private fun fixture(name: String) =
        requireNotNull(javaClass.getResource("/catalog/browse/$name.json")).readText()

    private fun HttpRequestData.body() =
        Json.parseToJsonElement((body as TextContent).text).jsonObject

    private fun continuationBody(ids: List<String>, next: String?): String {
        val artistBody = Json.parseToJsonElement(fixture("artist")).jsonObject
        val row =
            artistBody["contents"]!!
                .jsonObject["sectionListRenderer"]!!
                .jsonObject["contents"]!!
                .jsonArray[0]
                .jsonObject["musicShelfRenderer"]!!
                .jsonObject["contents"]!!
                .jsonArray[0]
                .toString()
        val shelf =
            mutableMapOf<String, JsonElement>(
                "contents" to
                    JsonArray(ids.map { Json.parseToJsonElement(row.replace("song-one", it)) })
            )
        if (next != null)
            shelf["continuations"] =
                JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "nextContinuationData" to
                                    JsonObject(mapOf("continuation" to JsonPrimitive(next)))
                            )
                        )
                    )
                )
        return JsonObject(
                mapOf(
                    "continuationContents" to
                        JsonObject(mapOf("musicShelfContinuation" to JsonObject(shelf)))
                )
            )
            .toString()
    }

    @Test
    fun linkedReleaseLoadsItsSourcePlaylistThroughAnonymousConfiguredClient() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val http =
            HttpClient(
                MockEngine { request ->
                    requests += request
                    respond(
                        fixture(
                            if (request.body()["browseId"]?.jsonPrimitive?.content == "album-one")
                                "album-linked"
                            else "album-inline"
                        ),
                        HttpStatusCode.OK,
                        headers,
                    )
                }
            ) {
                configureProviders()
            }
        try {
            val session =
                InnerTube(http, retryDelay = {}).apply { cookie = "SAPISID=synthetic-only" }
            val catalog = InnerTubeBrowseCatalog(session, 7, SessionIdentityStore()) { 10 }
            val result = catalog.album(album).valueOrNull()!!
            assertEquals("Numb", result.tracks.items.single().title)
            assertEquals(
                listOf("album-one", "VLOLAK-fixture"),
                requests.map { it.body()["browseId"]!!.jsonPrimitive.content },
            )
            assertTrue(
                requests.all {
                    it.url.host == "music.youtube.com" &&
                        it.url.encodedPath == "/youtubei/v1/browse"
                }
            )
            assertTrue(
                requests.all {
                    it.headers[HttpHeaders.Cookie] == null &&
                        it.headers[HttpHeaders.Authorization] == null
                }
            )
        } finally {
            http.close()
        }
    }

    @Test
    fun artistEndpointParamsContinueWithoutDuplicateRowsAndDetectTokenLoops() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val http =
            HttpClient(
                MockEngine { request ->
                    requests += request
                    val body = request.body()
                    val response =
                        when {
                            body["continuation"] != null ->
                                continuationBody(listOf("song-three"), "same-token")
                            body["browseId"]?.jsonPrimitive?.content == "artist-songs" ->
                                continuationBody(listOf("song-one", "song-two"), "same-token")
                            else -> fixture("artist")
                        }
                    respond(response, HttpStatusCode.OK, headers)
                }
            ) {
                configureProviders()
            }
        try {
            val catalog =
                InnerTubeBrowseCatalog(
                    InnerTube(http, retryDelay = {}),
                    1,
                    SessionIdentityStore(),
                ) {
                    0
                }
            val first = catalog.artist(artist).valueOrNull()!!
            val second = catalog.moreTracks(first.tracks.next!!).valueOrNull()!!
            assertEquals(1, second.items.size)
            assertEquals("song-two", second.items.single().candidates.single().source.id)
            assertEquals("songs-params", requests[1].body()["params"]!!.jsonPrimitive.content)
            val loop = catalog.moreTracks(second.next!!)
            assertEquals(ErrorCode.PROVIDER_CHANGED, loop.errorOrNull()?.code)
            assertEquals(3, requests.size)
            assertEquals(
                "same-token",
                requests.last().body()["continuation"]!!.jsonPrimitive.content,
            )
            assertEquals(
                ErrorCode.INVALID_CONFIG,
                catalog.moreAlbums(first.tracks.next!!).errorOrNull()?.code,
            )
        } finally {
            http.close()
        }
    }

    @Test
    fun missingSourceAndOldOrUnknownCursorNeverCreateRequests() = runTest {
        var requests = 0
        val http =
            HttpClient(
                MockEngine {
                    requests++
                    respond(fixture("artist"), HttpStatusCode.OK, headers)
                }
            ) {
                configureProviders()
            }
        try {
            val tube = InnerTube(http, retryDelay = {})
            val old = InnerTubeBrowseCatalog(tube, 1, SessionIdentityStore()) { 0 }
            val cursor = old.artist(artist).valueOrNull()!!.tracks.next!!
            val current = InnerTubeBrowseCatalog(tube, 2, SessionIdentityStore()) { 0 }
            assertEquals(
                ErrorCode.SOURCE_UNAVAILABLE,
                current.moreTracks(cursor).errorOrNull()?.code,
            )
            assertEquals(
                ErrorCode.SOURCE_UNAVAILABLE,
                current.moreTracks(CatalogCursor("unknown")).errorOrNull()?.code,
            )
            assertEquals(
                ErrorCode.SOURCE_UNAVAILABLE,
                current.album(album.copy(source = null)).errorOrNull()?.code,
            )
            assertEquals(
                ErrorCode.SOURCE_UNAVAILABLE,
                current
                    .artist(artist.copy(source = SourceRef("other", "artist", "foreign")))
                    .errorOrNull()
                    ?.code,
            )
            assertEquals(1, requests)
        } finally {
            http.close()
        }
    }

    @Test
    fun routeChangeDiscardsInFlightResponseAndDoesNotUseAnotherClient() = runTest {
        val gate = CompletableDeferred<Unit>()
        var network =
            NetworkContext(
                1,
                NetworkConfig(NetworkMode.SOCKS5, ProxyEndpoint("proxy.example", 1080), null),
                true,
                true,
            )
        var calls = 0
        val delegate =
            object : BrowseCatalog {
                override suspend fun album(ref: AlbumRef): AppResult<AlbumCatalog> {
                    calls++
                    gate.await()
                    return AppResult.Success(
                        AlbumCatalog(
                            ref,
                            emptyList(),
                            Artwork(ArtworkKind.PLACEHOLDER, null, null),
                            CatalogPage(emptyList(), null),
                        )
                    )
                }

                override suspend fun artist(ref: ArtistRef): AppResult<ArtistCatalog> =
                    error("Unexpected")

                override suspend fun moreTracks(
                    cursor: CatalogCursor
                ): AppResult<CatalogPage<Track>> = error("Unexpected")

                override suspend fun moreAlbums(
                    cursor: CatalogCursor
                ): AppResult<CatalogPage<AlbumCard>> = error("Unexpected")
            }
        val routed = RoutedBrowseCatalog({ delegate }, { network })
        val pending = async { routed.album(album) }
        runCurrent()
        network = network.copy(generation = 2)
        gate.complete(Unit)
        assertFailsWith<CancellationException> { pending.await() }
        network = network.copy(ready = false)
        assertEquals(ErrorCode.NETWORK, routed.album(album).errorOrNull()?.code)
        assertEquals(1, calls)
    }

    @Test
    fun providerFailureDoesNotConsumeCursorAndRetryKeepsLoadedData() = runTest {
        var fail = true
        val http =
            HttpClient(
                MockEngine { request ->
                    if (request.body()["browseId"]?.jsonPrimitive?.content == "artist-one")
                        respond(fixture("artist"), HttpStatusCode.OK, headers)
                    else if (fail) respond("{}", HttpStatusCode.ServiceUnavailable, headers)
                    else
                        respond(
                            continuationBody(listOf("song-two"), null),
                            HttpStatusCode.OK,
                            headers,
                        )
                }
            ) {
                configureProviders()
            }
        try {
            val catalog =
                InnerTubeBrowseCatalog(
                    InnerTube(http, retryDelay = {}),
                    1,
                    SessionIdentityStore(),
                ) {
                    0
                }
            val first = catalog.artist(artist).valueOrNull()!!
            assertEquals(
                ErrorCode.NETWORK,
                catalog.moreTracks(first.tracks.next!!).errorOrNull()?.code,
            )
            assertEquals(1, first.tracks.items.size)
            fail = false
            assertEquals(1, catalog.moreTracks(first.tracks.next!!).valueOrNull()!!.items.size)
        } finally {
            http.close()
        }
    }
}
