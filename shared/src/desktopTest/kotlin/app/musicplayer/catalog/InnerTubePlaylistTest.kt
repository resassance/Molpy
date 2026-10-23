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
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

class InnerTubePlaylistTest {
    private val ref = PublicPlaylistRef("Rock picks", SourceRef("youtube", "playlist", "PLfixture"))
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")

    private fun fixture(name: String) =
        requireNotNull(javaClass.getResource("/catalog/playlists/$name.json")).readText()

    @Test
    fun concurrentSameCursorSharesRequest() = runTest {
        val gate = CompletableDeferred<Unit>()
        var tailRequests = 0
        val http =
            HttpClient(
                MockEngine { request ->
                    assertNull(request.headers[HttpHeaders.Cookie])
                    assertNull(request.headers[HttpHeaders.Authorization])
                    val tail =
                        Json.parseToJsonElement((request.body as TextContent).text)
                            .jsonObject["continuation"] != null
                    if (tail) {
                        tailRequests++
                        gate.await()
                    }
                    respond(fixture(if (tail) "tail" else "initial"), HttpStatusCode.OK, headers)
                }
            ) {
                configureProviders()
            }
        try {
            val catalog =
                InnerTubeBrowseCatalog(
                    InnerTube(http, retryDelay = {}).apply { cookie = "SAPISID=synthetic-only" },
                    7,
                    SessionIdentityStore(),
                )
            val first = catalog.playlist(ref).valueOrNull()!!
            val a = async { catalog.morePlaylist(first.tracks.next!!) }
            val b = async { catalog.morePlaylist(first.tracks.next!!) }
            runCurrent()
            gate.complete(Unit)
            assertEquals(a.await(), b.await())
            assertEquals(1, tailRequests)
            assertEquals(
                3,
                catalog
                    .morePlaylist(first.tracks.next!!)
                    .valueOrNull()!!
                    .items
                    .single()
                    .identity
                    .ordinal,
            )
            assertEquals(1, tailRequests)
        } finally {
            http.close()
        }
    }

    @Test
    fun refreshGetsNewHead() = runTest {
        var calls = 0
        val http =
            HttpClient(
                MockEngine {
                    calls++
                    respond(
                        fixture("initial").replace("Rock picks", "Revision $calls"),
                        HttpStatusCode.OK,
                        headers,
                    )
                }
            ) {
                configureProviders()
            }
        try {
            val catalog =
                InnerTubeBrowseCatalog(InnerTube(http, retryDelay = {}), 1, SessionIdentityStore())
            assertEquals("Revision 1", catalog.playlist(ref).valueOrNull()!!.card.ref.title)
            assertEquals("Revision 1", catalog.playlist(ref).valueOrNull()!!.card.ref.title)
            assertEquals(
                "Revision 2",
                catalog.playlist(ref, refresh = true).valueOrNull()!!.card.ref.title,
            )
        } finally {
            http.close()
        }
    }

    @Test
    fun playlistCursorCannotReadAlbumPage() = runTest {
        val http =
            HttpClient(MockEngine { respond(fixture("initial"), HttpStatusCode.OK, headers) }) {
                configureProviders()
            }
        try {
            val catalog =
                InnerTubeBrowseCatalog(InnerTube(http, retryDelay = {}), 1, SessionIdentityStore())
            val cursor = catalog.playlist(ref).valueOrNull()!!.tracks.next!!
            assertEquals(ErrorCode.INVALID_CONFIG, catalog.moreTracks(cursor).errorOrNull()!!.code)
            assertEquals(ErrorCode.INVALID_CONFIG, catalog.moreAlbums(cursor).errorOrNull()!!.code)
        } finally {
            http.close()
        }
    }

    @Test
    fun failedPageIsNotCached() = runTest {
        var tails = 0
        val http =
            HttpClient(
                MockEngine { request ->
                    val tail =
                        Json.parseToJsonElement((request.body as TextContent).text)
                            .jsonObject["continuation"] != null
                    if (tail) tails++
                    respond(
                        fixture(if (!tail) "initial" else if (tails == 1) "unknown" else "tail"),
                        HttpStatusCode.OK,
                        headers,
                    )
                }
            ) {
                configureProviders()
            }
        try {
            val catalog =
                InnerTubeBrowseCatalog(InnerTube(http, retryDelay = {}), 1, SessionIdentityStore())
            val cursor = catalog.playlist(ref).valueOrNull()!!.tracks.next!!
            assertEquals(
                ErrorCode.PROVIDER_CHANGED,
                catalog.morePlaylist(cursor).errorOrNull()!!.code,
            )
            assertEquals(
                3,
                catalog.morePlaylist(cursor).valueOrNull()!!.items.single().identity.ordinal,
            )
            assertEquals(2, tails)
        } finally {
            http.close()
        }
    }
}
