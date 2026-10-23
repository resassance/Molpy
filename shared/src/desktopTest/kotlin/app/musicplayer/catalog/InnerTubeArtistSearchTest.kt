package app.musicplayer.catalog

import app.musicplayer.artists.*
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

class InnerTubeArtistSearchTest {
    private val hit = ArtistCatalogHit("Old name", SourceRef("youtube", "artist", "UC-one"), null)

    @Test
    fun concurrentQueriesKeepTheirOwnResults() = runTest {
        val slowStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val http =
            HttpClient(
                MockEngine { request ->
                    val query =
                        Json.parseToJsonElement((request.body as TextContent).text)
                            .jsonObject["query"]!!
                            .jsonPrimitive
                            .content
                    if (query == "slow") {
                        slowStarted.complete(Unit)
                        release.await()
                    }
                    respond(
                        artistSearchFixture("mixed").replace("Artist One", query),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                configureProviders()
            }
        try {
            val session = InnerTube(http, retryDelay = {})
            val adapter =
                InnerTubeArtistSearch(
                    session,
                    InnerTubeBrowseCatalog(session, 1, SessionIdentityStore()),
                )
            val first = async { adapter.search("slow") }
            slowStarted.await()
            val second = async { adapter.search("fast") }
            assertEquals("fast", assertNotNull(second.await().valueOrNull()).single().name)
            release.complete(Unit)
            assertEquals("slow", assertNotNull(first.await().valueOrNull()).single().name)
        } finally {
            http.close()
        }
    }

    @Test
    fun searchUsesAnonymousMusicFilterAndValidationFetchesArtistPage() = runTest {
        val bodies = mutableListOf<JsonObject>()
        val http =
            HttpClient(
                MockEngine { request ->
                    assertEquals("music.youtube.com", request.url.host)
                    assertNull(request.headers[HttpHeaders.Cookie])
                    assertNull(request.headers[HttpHeaders.Authorization])
                    val body =
                        Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    bodies += body
                    val content =
                        if (request.url.encodedPath.endsWith("/search"))
                            artistSearchFixture("mixed")
                        else browseFixture("artist")
                    respond(
                        content,
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
            val adapter =
                InnerTubeArtistSearch(
                    session,
                    InnerTubeBrowseCatalog(session, 1, SessionIdentityStore()),
                )
            assertEquals(
                listOf("UC-one"),
                assertNotNull(adapter.search("artist").valueOrNull()).map { it.source.id },
            )
            val validated = assertNotNull(adapter.validate(hit).valueOrNull())
            assertEquals("Linkin Park", validated.name)
            assertEquals(hit.source, validated.source)
            assertEquals(
                "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D",
                bodies.first()["params"]?.jsonPrimitive?.content,
            )
            assertEquals("UC-one", bodies.last()["browseId"]?.jsonPrimitive?.content)
        } finally {
            http.close()
        }
    }

    @Test
    fun claimedArtistKindDoesNotValidateAnAlbumOrPodcastPage() = runTest {
        for (name in listOf("album-inline", "artist")) {
            val body =
                if (name == "album-inline") browseFixture(name)
                else
                    browseFixture(name)
                        .replace("musicImmersiveHeaderRenderer", "musicVisualHeaderRenderer")
            val http =
                HttpClient(
                    MockEngine {
                        respond(
                            body,
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                ) {
                    configureProviders()
                }
            try {
                val session = InnerTube(http, retryDelay = {})
                val adapter =
                    InnerTubeArtistSearch(
                        session,
                        InnerTubeBrowseCatalog(session, 1, SessionIdentityStore()),
                    )
                assertIs<AppResult.Failure>(adapter.validate(hit))
                assertIs<AppResult.Failure>(
                    adapter.validate(
                        hit.copy(source = SourceRef("youtube", "channel", "UC-channel"))
                    )
                )
            } finally {
                http.close()
            }
        }
    }

    @Test
    fun errorsRemainTypedAndCancellationPropagates() = runTest {
        for ((status, code) in
            listOf(
                403 to ErrorCode.SOURCE_UNAVAILABLE,
                407 to ErrorCode.PROXY_AUTH,
                429 to ErrorCode.RATE_LIMIT,
                503 to ErrorCode.NETWORK,
            )) {
            val http =
                HttpClient(
                    MockEngine {
                        respond(
                            "{}",
                            HttpStatusCode.fromValue(status),
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                ) {
                    configureProviders()
                }
            try {
                val session = InnerTube(http, retryDelay = {})
                assertEquals(
                    code,
                    InnerTubeArtistSearch(
                            session,
                            InnerTubeBrowseCatalog(session, 1, SessionIdentityStore()),
                        )
                        .search("artist")
                        .errorOrNull()
                        ?.code,
                )
            } finally {
                http.close()
            }
        }
        val http =
            HttpClient(MockEngine { throw CancellationException("cancelled") }) {
                configureProviders()
            }
        try {
            val session = InnerTube(http, retryDelay = {})
            assertFailsWith<CancellationException> {
                InnerTubeArtistSearch(
                        session,
                        InnerTubeBrowseCatalog(session, 1, SessionIdentityStore()),
                    )
                    .search("artist")
            }
        } finally {
            http.close()
        }
    }

    @Test
    fun routeChangeRejectsLateResultAndUnavailableRouteDoesNotCallProvider() = runTest {
        var generation = 1L
        var ready = true
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val port =
            object : ArtistSearchPort {
                override suspend fun search(query: String): AppResult<List<ArtistCatalogHit>> {
                    calls++
                    started.complete(Unit)
                    finish.await()
                    return AppResult.Success(listOf(hit))
                }

                override suspend fun validate(hit: ArtistCatalogHit) = AppResult.Success(hit)
            }
        val routed =
            RoutedArtistSearch(
                { port },
                {
                    NetworkContext(
                        generation,
                        NetworkConfig(NetworkMode.SYSTEM, null, null),
                        ready,
                        true,
                    )
                },
            )
        val pending = async { routed.search("artist") }
        started.await()
        generation++
        finish.complete(Unit)
        assertFailsWith<CancellationException> { pending.await() }
        ready = false
        assertEquals(ErrorCode.NETWORK, routed.search("artist").errorOrNull()?.code)
        assertEquals(1, calls)
    }

    private fun browseFixture(name: String) =
        requireNotNull(javaClass.getResource("/catalog/browse/$name.json")).readText()
}
