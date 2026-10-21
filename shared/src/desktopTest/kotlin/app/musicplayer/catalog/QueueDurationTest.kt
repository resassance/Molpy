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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class QueueDurationTest {
    @Test
    fun durationsUseExactIdsAndAnAnonymousBatch() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val http =
            HttpClient(
                MockEngine { request ->
                    requests += request
                    respond(
                        """{"queueDatas":[
              {"content":{"playlistPanelVideoRenderer":{"videoId":"a","lengthText":{"runs":[{"text":"3:08"}]}}}},
              {"content":{"playlistPanelVideoRenderer":{"videoId":"b","lengthText":{"simpleText":"1:02:03"}}}},
              {"content":{"playlistPanelVideoRenderer":{"videoId":"extra","lengthText":{"simpleText":"4:00"}}}},
              {"content":{"playlistPanelVideoRenderer":{"videoId":"missing"}}}
            ]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                configureProviders()
            }
        try {
            val tube = InnerTube(http)
            tube.cookie = "SAPISID=synthetic-only"
            val catalog = InnerTubeCatalog(tube, SessionIdentityStore())
            val a = SourceRef("youtube", "video", "a")
            val b = a.copy(id = "b")
            val values =
                assertNotNull(
                    catalog.durations(listOf(a, b, a, a.copy(id = "missing"))).valueOrNull()
                )
            assertEquals(mapOf(a to 188_000L, b to 3_723_000L), values)
            val request = requests.single()
            assertEquals("/youtubei/v1/music/get_queue", request.url.encodedPath)
            assertNull(request.headers[HttpHeaders.Cookie])
            assertNull(request.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals(
                listOf("a", "b", "missing"),
                body["videoIds"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(
                "WEB_REMIX",
                body["context"]!!
                    .jsonObject["client"]!!
                    .jsonObject["clientName"]!!
                    .jsonPrimitive
                    .content,
            )
        } finally {
            http.close()
        }
    }

    @Test
    fun changedSchemaIsFailureRatherThanInventedDuration() = runTest {
        val http =
            HttpClient(
                MockEngine {
                    respond(
                        """{"unexpected":[]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                configureProviders()
            }
        try {
            val result =
                InnerTubeCatalog(InnerTube(http), SessionIdentityStore())
                    .durations(listOf(SourceRef("youtube", "video", "a")))
            assertEquals(ErrorCode.PROVIDER_CHANGED, result.errorOrNull()?.code)
        } finally {
            http.close()
        }
    }
}
