package app.musicplayer.catalog

import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class MusicSearchMapperTest {
    private fun fixture(name: String) =
        requireNotNull(javaClass.getResource("/catalog/search/$name.json")).readText()

    private val mapper = SearchMapper(SessionIdentityStore()) { 10 }

    @Test
    fun artistTopCardUsesOwnEndpoint() = runTest {
        val page = mapper.map(fixture("summary"), SearchFilter.ALL).valueOrNull()!!.page
        assertEquals("artist-one", assertIs<SearchItem.ArtistItem>(page.topResult).ref.source!!.id)
        assertEquals("Linkin Park", assertIs<SearchItem.ArtistItem>(page.topResult).ref.name)
    }

    @Test
    fun topResultPrecedesSourceOrderedSections() = runTest {
        val page = mapper.map(fixture("summary"), SearchFilter.ALL).valueOrNull()!!.page
        assertEquals(
            listOf(
                "artist:youtube/artist/artist-one",
                "track:youtube/video/song-one",
                "album:youtube/album/album-one",
                "playlist:youtube/playlist/PLfixture",
            ),
            page.items().map { it.key },
        )
        assertEquals(187000L, page.tracks().single().durationMs)
        assertEquals("Meteora", page.tracks().single().album!!.name)
    }

    @Test
    fun foreignShelfDoesNotEscapeFilter() = runTest {
        val page = mapper.map(fixture("summary"), SearchFilter.ALBUMS).valueOrNull()!!.page
        assertEquals(listOf("album:youtube/album/album-one"), page.items().map { it.key })
    }

    @Test
    fun absentTopCardDoesNotInventBestResult() = runTest {
        val page = mapper.map(fixture("no-top"), SearchFilter.ALL).valueOrNull()!!.page
        assertNull(page.topResult)
        assertIs<SearchItem.AlbumItem>(page.items().first())
    }

    @Test
    fun validEmptyDiffersFromUnknownSchema() = runTest {
        assertTrue(
            mapper.map(fixture("empty"), SearchFilter.ALL).valueOrNull()!!.page.items().isEmpty()
        )
        assertEquals(
            ErrorCode.PROVIDER_CHANGED,
            mapper.map(fixture("unknown"), SearchFilter.ALL).errorOrNull()!!.code,
        )
    }

    @Test
    fun editorialPlaylistIsNotRejectedAsRadio() = runTest {
        val page =
            mapper
                .map(
                    fixture("summary").replace("VLPLfixture", "VLRDCLAK5uy_fixture"),
                    SearchFilter.PLAYLISTS,
                )
                .valueOrNull()!!
                .page
        assertEquals(
            "RDCLAK5uy_fixture",
            assertIs<SearchItem.PlaylistItem>(page.items().single()).card.ref.source.id,
        )
    }

    @Test
    fun damagedNeighborPreservesUsableResultsWithWarning() = runTest {
        val body = fixture("summary").replace("\"browseId\": \"album-one\"", "\"browseId\": \"\"")
        val page = mapper.map(body, SearchFilter.ALL).valueOrNull()!!
        assertEquals(
            "artist-one",
            assertIs<SearchItem.ArtistItem>(page.page.topResult).ref.source!!.id,
        )
        assertEquals(ErrorCode.PROVIDER_CHANGED, page.page.primaryFailure!!.code)
        assertTrue(page.page.items().any { it is SearchItem.PlaylistItem })
    }

    @Test
    fun adjacentTrackShelvesShareOneHeadingWithoutReorderingOtherTypes() = runTest {
        val root = Json.parseToJsonElement(fixture("summary")).jsonObject
        val shelves = catalogSections(root)
        val song = shelves.first {
            it["musicShelfRenderer"]
                ?.jsonObject
                ?.get("contents")
                ?.toString()
                ?.contains("song-one") == true
        }
        val album = shelves.first {
            it["musicShelfRenderer"]
                ?.jsonObject
                ?.get("contents")
                ?.toString()
                ?.contains("album-one") == true
        }
        val second = Json.parseToJsonElement(song.toString().replace("song-one", "song-two"))
        val third = Json.parseToJsonElement(song.toString().replace("song-one", "song-three"))
        val body = buildJsonObject {
            put(
                "contents",
                buildJsonObject {
                    put(
                        "sectionListRenderer",
                        buildJsonObject {
                            put("contents", JsonArray(listOf(song, second, album, third)))
                        },
                    )
                },
            )
        }
            .toString()
        val page = assertNotNull(mapper.map(body, SearchFilter.ALL).valueOrNull()).page
        assertEquals(
            listOf(SearchFilter.TRACKS, SearchFilter.ALBUMS, SearchFilter.TRACKS),
            page.sections.map { it.filter },
        )
        assertEquals(
            listOf("song-one", "song-two", "album-one", "song-three"),
            page.items().map { it.source.id },
        )
    }
}
