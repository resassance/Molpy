package app.musicplayer.catalog

import app.musicplayer.model.*
import app.musicplayer.network.configureProviders
import com.metrolist.innertubex.InnerTube
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class InnerTubeArtistArtworkSourceTest {
    private val source = SourceRef("youtube", "artist", "UC-one")

    @Test
    fun fetchIsOneAnonymousMusicBrowseWithoutContinuation() = runTest {
        var requests = 0
        val http =
            HttpClient(
                MockEngine { request ->
                    requests++
                    assertTrue(request.url.encodedPath.endsWith("/browse"))
                    assertEquals("music.youtube.com", request.url.host)
                    assertNull(request.headers[HttpHeaders.Cookie])
                    assertNull(request.headers[HttpHeaders.Authorization])
                    val body =
                        Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals("UC-one", body["browseId"]?.jsonPrimitive?.content)
                    assertNull(body["continuation"])
                    assertNull(body["params"])
                    assertEquals(
                        "WEB_REMIX",
                        body["context"]
                            ?.jsonObject
                            ?.get("client")
                            ?.jsonObject
                            ?.get("clientName")
                            ?.jsonPrimitive
                            ?.content,
                    )
                    respond(
                        artistPhotoFixture().dropLast(1) + ",\"contents\":42}",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                configureProviders()
            }
        try {
            val session = InnerTube(http, retryDelay = {})
            session.cookie = "SAPISID=synthetic-test-only"
            assertEquals(
                "https://img.example/large",
                InnerTubeArtistArtworkSource(session).fetch(source).valueOrNull()?.remoteUrl,
            )
            assertEquals(1, requests)
        } finally {
            http.close()
        }
    }

    @Test
    fun invalidSourceNeverReachesTransport() = runTest {
        val http = HttpClient(MockEngine { error("Unexpected request") }) { configureProviders() }
        try {
            val adapter = InnerTubeArtistArtworkSource(InnerTube(http, retryDelay = {}))
            assertEquals(
                ErrorCode.SOURCE_UNAVAILABLE,
                adapter.fetch(source.copy(kind = "track")).errorOrNull()?.code,
            )
        } finally {
            http.close()
        }
    }

    @Test
    fun httpFailuresAreNotConfirmedAbsence() = runTest {
        for ((status, expected) in
            listOf(
                404 to ErrorCode.SOURCE_UNAVAILABLE,
                429 to ErrorCode.RATE_LIMIT,
                503 to ErrorCode.NETWORK,
            )) {
            val http =
                HttpClient(MockEngine { respond("{}", HttpStatusCode.fromValue(status)) }) {
                    configureProviders()
                }
            try {
                assertEquals(
                    expected,
                    InnerTubeArtistArtworkSource(InnerTube(http, retryDelay = {}))
                        .fetch(source)
                        .errorOrNull()
                        ?.code,
                )
            } finally {
                http.close()
            }
        }
    }

    @Test
    fun cancellationPropagates() = runTest {
        val http =
            HttpClient(MockEngine { throw CancellationException("cancelled") }) {
                configureProviders()
            }
        try {
            assertFailsWith<CancellationException> {
                InnerTubeArtistArtworkSource(InnerTube(http, retryDelay = {})).fetch(source)
            }
        } finally {
            http.close()
        }
    }
}
