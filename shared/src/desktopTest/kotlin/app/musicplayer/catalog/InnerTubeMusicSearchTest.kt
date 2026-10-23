package app.musicplayer.catalog

import app.musicplayer.model.*
import app.musicplayer.network.configureProviders
import com.metrolist.innertubex.InnerTube
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class InnerTubeMusicSearchTest {
    private fun fixture(name: String) =
        requireNotNull(javaClass.getResource("/catalog/search/$name.json")).readText()

    @Test
    fun typedSearchUsesAnonymousConfiguredClientAndOpaqueContinuation() = runTest {
        val requests = mutableListOf<JsonObject>()
        val tokens = mutableListOf<String?>()
        val http =
            HttpClient(
                MockEngine { request ->
                    assertEquals("music.youtube.com", request.url.host)
                    assertNull(request.headers[HttpHeaders.Cookie])
                    assertNull(request.headers[HttpHeaders.Authorization])
                    val body =
                        Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    requests += body
                    tokens += request.url.parameters["continuation"]
                    respond(
                        fixture(
                            if (request.url.parameters["continuation"] != null) "continuation"
                            else "songs"
                        ),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                configureProviders()
            }
        try {
            val catalog =
                InnerTubeMusicSearch(InnerTube(http, retryDelay = {}), 4, SessionIdentityStore()) {
                    10
                }
            val first = catalog.search("Numb", SearchFilter.TRACKS).valueOrNull()!!
            assertEquals("song-one", first.tracks().single().candidates.single().source.id)
            assertFalse(first.next!!.value.contains("page-two"))
            val second = catalog.more(first.next!!).valueOrNull()!!
            assertEquals("song-two", second.tracks().single().candidates.single().source.id)
            assertNull(second.next)
            assertEquals(2, requests.size)
            assertEquals("page-two", tokens.last())
        } finally {
            http.close()
        }
    }

    @Test
    fun staleCursorRejectedAfterNetworkChange() = runTest {
        val http =
            HttpClient(
                MockEngine {
                    respond(
                        fixture("songs"),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                configureProviders()
            }
        try {
            val session = InnerTube(http, retryDelay = {})
            val first =
                InnerTubeMusicSearch(session, 4, SessionIdentityStore())
                    .search("Numb", SearchFilter.TRACKS)
                    .valueOrNull()!!
            assertIs<AppResult.Failure>(
                InnerTubeMusicSearch(session, 5, SessionIdentityStore()).more(first.next!!)
            )
        } finally {
            http.close()
        }
    }
}
