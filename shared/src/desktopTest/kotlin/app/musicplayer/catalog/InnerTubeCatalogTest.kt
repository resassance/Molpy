package app.musicplayer.catalog

import app.musicplayer.model.*
import app.musicplayer.network.configureProviders
import com.metrolist.innertubex.InnerTube
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.io.IOException
import kotlin.test.*

class InnerTubeCatalogTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun applicationHttpConfigurationSerializesRealCatalogRequests() = runTest {
        val http = HttpClient(MockEngine { respond(fixture("song"), HttpStatusCode.OK, headers) }) {
            configureProviders()
        }
        try {
            val result = InnerTubeCatalog(InnerTube(http), SessionIdentityStore()).searchSongs("Numb")
            assertEquals("Numb", assertNotNull(result.valueOrNull()).single().title)
            assertEquals("Meteora", assertNotNull(result.valueOrNull()).single().album?.name)
        } finally { http.close() }
    }

    @Test fun songsUseAnonymousWebRemixSongSearch() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val http = client { request -> requests.add(request); respond(fixture("song"), HttpStatusCode.OK, headers) }
        try {
            val tube = InnerTube(http, retryDelay = {})
            tube.cookie = "SAPISID=synthetic-test-only"
            val catalog: CatalogPort = InnerTubeCatalog(tube, SessionIdentityStore())
            val songs = assertNotNull(catalog.searchSongs("Numb").valueOrNull())
            assertEquals("Meteora", songs.single().album?.name)
            val request = requests.single()
            assertEquals("music.youtube.com", request.url.host)
            assertEquals("/youtubei/v1/search", request.url.encodedPath)
            val body = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("WEB_REMIX", body["context"]!!.jsonObject["client"]!!.jsonObject["clientName"]!!.jsonPrimitive.content)
            assertEquals("Numb", body["query"]!!.jsonPrimitive.content)
            assertEquals("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D", body["params"]!!.jsonPrimitive.content)
            assertNull(request.headers[HttpHeaders.Cookie])
            assertNull(request.headers[HttpHeaders.Authorization])
        } finally { http.close() }
    }

    @Test fun ordinaryVideosUseWebEndpointAndVideoFilter() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val http = client { request -> requests.add(request); respond(fixture("video"), HttpStatusCode.OK, headers) }
        try {
            val result = InnerTubeCatalog(InnerTube(http), SessionIdentityStore()).searchVideos("Numb")
            assertTrue(assertNotNull(result.valueOrNull()).single().provenance.tentative)
            val request = requests.single()
            assertEquals("www.youtube.com", request.url.host)
            val body = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("WEB", body["context"]!!.jsonObject["client"]!!.jsonObject["clientName"]!!.jsonPrimitive.content)
            assertEquals("EgIQAQ%3D%3D", body["params"]!!.jsonPrimitive.content)
        } finally { http.close() }
    }

    @Test fun findingMusicCandidatesUsesTitleAndCreditsWithoutLinkingIdentities() = runTest {
        var query: String? = null
        val identities = SessionIdentityStore()
        val video = assertNotNull(CatalogMapper(identities).mapVideos(fixture("video")).valueOrNull()).single()
        val http = client { request ->
            query = json.parseToJsonElement((request.body as TextContent).text).jsonObject["query"]!!.jsonPrimitive.content
            respond(fixture("song").replace("song-one", "music-another"), HttpStatusCode.OK, headers)
        }
        try {
            val matches = assertNotNull(InnerTubeCatalog(InnerTube(http), identities).findMusicMatches(video).valueOrNull())
            assertEquals("Linkin Park - Numb (Official Video) Uploader", query)
            assertNotEquals(video.id, matches.single().id)
            assertNull(video.album)
        } finally { http.close() }
    }

    @Test fun malformedProviderResponseIsNotSuccessfulEmptySearch() = runTest {
        val http = client { respond(fixture("changed-schema"), HttpStatusCode.OK, headers) }
        try {
            val error = InnerTubeCatalog(InnerTube(http), SessionIdentityStore()).searchSongs("test").errorOrNull()
            assertEquals(ErrorCode.PROVIDER_CHANGED, error?.code)
            assertFalse(assertNotNull(error).retryable)
        } finally { http.close() }
    }

    @Test fun networkFailureKeepsNetworkCategory() = runTest {
        val http = client { throw IOException("synthetic outage") }
        try {
            val error = InnerTubeCatalog(InnerTube(http, retryDelay = {}), SessionIdentityStore()).searchSongs("test").errorOrNull()
            assertEquals(ErrorCode.NETWORK, error?.code)
            assertTrue(assertNotNull(error).retryable)
        } finally { http.close() }
    }

    @Test fun httpFailuresAreNormalizedWithoutEmptyResults() = runTest {
        for ((status, code) in listOf(401 to ErrorCode.LOGIN_REQUIRED, 403 to ErrorCode.SOURCE_UNAVAILABLE, 407 to ErrorCode.PROXY_AUTH, 429 to ErrorCode.RATE_LIMIT, 503 to ErrorCode.NETWORK)) {
            val http = client { respond("{}", HttpStatusCode.fromValue(status), headers) }
            try {
                val error = InnerTubeCatalog(InnerTube(http, retryDelay = {}), SessionIdentityStore()).searchVideos("test").errorOrNull()
                assertEquals(code, error?.code, "HTTP $status")
            } finally { http.close() }
        }
    }

    @Test fun cancellationPropagatesThroughAdapter() = runTest {
        val http = client { throw CancellationException("synthetic cancellation") }
        try {
            assertFailsWith<CancellationException> { InnerTubeCatalog(InnerTube(http), SessionIdentityStore()).searchSongs("test") }
        } finally { http.close() }
    }

    @Test fun blankQueryDoesNotReachProvider() = runTest {
        val http = client { fail("Blank query must not make a network request") }
        try {
            assertEquals(ErrorCode.INVALID_CONFIG, InnerTubeCatalog(InnerTube(http), SessionIdentityStore()).searchSongs(" ").errorOrNull()?.code)
        } finally { http.close() }
    }

    @Test fun emptyProviderResultRemainsSuccessful() = runTest {
        val http = client { respond("""{"contents":{"sectionListRenderer":{"contents":[]}}}""", HttpStatusCode.OK, headers) }
        try {
            assertEquals(emptyList(), assertNotNull(InnerTubeCatalog(InnerTube(http), SessionIdentityStore()).searchSongs("test").valueOrNull()))
        } finally { http.close() }
    }

    private fun client(handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = HttpClient(MockEngine { handler(it) }) {
        install(ContentNegotiation) { json(json) }
    }
    private fun fixture(name: String) = checkNotNull(javaClass.getResource("/catalog/$name.json")).readText()
}
