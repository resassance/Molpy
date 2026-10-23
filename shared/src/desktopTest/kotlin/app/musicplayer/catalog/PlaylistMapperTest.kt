package app.musicplayer.catalog

import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class PlaylistMapperTest {
    private val ref = PublicPlaylistRef("Rock picks", SourceRef("youtube", "playlist", "PLfixture"))

    private fun fixture(name: String) =
        requireNotNull(javaClass.getResource("/catalog/playlists/$name.json")).readText()

    private val mapper = PlaylistMapper(SessionIdentityStore()) { 10 }

    @Test
    fun duplicateSongsKeepDistinctOccurrences() = runTest {
        val page = mapper.playlist(fixture("initial"), ref).valueOrNull()!!.tracks
        assertEquals(listOf(0, 1, 2), page.items.map { it.identity.ordinal })
        assertEquals(listOf("entry-a", null, "entry-b"), page.items.map { it.identity.itemId })
        val tracks = page.items.mapNotNull { it.track }
        assertEquals(2, tracks.size)
        assertEquals(tracks[0].id, tracks[1].id)
        assertEquals("playlist-tail", page.next!!.continuation)
    }

    @Test
    fun playlistOwnerAndCoverNeverOverwriteTrack() = runTest {
        for (layout in listOf("initial", "column")) {
            val mapped = mapper.playlist(fixture(layout), ref).valueOrNull()!!
            assertEquals("Curator", mapped.card.owner)
            assertEquals(
                "https://example.test/playlist-collage.jpg",
                mapped.card.artwork!!.remoteUrl,
            )
            val track = mapped.tracks.items.first().track!!
            assertEquals("Linkin Park", track.artists.single().name)
            assertEquals("Meteora", track.album!!.name)
            assertEquals("https://example.test/video-shaped.jpg", track.artwork.remoteUrl)
        }
    }

    @Test
    fun unavailableRowPreservesOrdinal() = runTest {
        val row = mapper.playlist(fixture("initial"), ref).valueOrNull()!!.tracks.items[1]
        assertEquals(1, row.identity.ordinal)
        assertTrue(row.unavailable)
        assertNull(row.track)
        assertNull(row.identity.playbackSource)
    }

    @Test
    fun allContinuationLayoutsKeepSourcePositionAndDoNotInventMorePages() = runTest {
        for (layout in listOf("tail", "tail-shelf", "tail-section", "tail-actions")) {
            val page = mapper.more(fixture(layout), 3).valueOrNull()!!
            assertEquals(3, page.items.single().identity.ordinal)
            assertEquals("entry-c", page.items.single().identity.itemId)
            assertNull(page.next)
        }
    }

    @Test
    fun emptyPlaylistDiffersFromChangedSchema() = runTest {
        assertTrue(mapper.playlist(fixture("empty"), ref).valueOrNull()!!.tracks.items.isEmpty())
        assertEquals(
            ErrorCode.PROVIDER_CHANGED,
            mapper.playlist(fixture("unknown"), ref).errorOrNull()!!.code,
        )
    }
}
