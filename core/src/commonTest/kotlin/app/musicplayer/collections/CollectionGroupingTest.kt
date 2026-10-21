package app.musicplayer.collections

import app.musicplayer.fixtures.Fixtures
import app.musicplayer.model.*
import kotlin.test.*

class CollectionGroupingTest {
    @Test
    fun sourceIdentitySeparatesNamesAndReleaseArtworkWins() {
        val a =
            Fixtures.track("a", sourceId = "a")
                .copy(
                    album = AlbumRef("Same", SourceRef("yt", "album", "a")),
                    artists = listOf(ArtistRef("Same", SourceRef("yt", "artist", "a"))),
                    artwork = Artwork(ArtworkKind.VIDEO, "https://art/a", null),
                )
        val b =
            Fixtures.track("b", sourceId = "b")
                .copy(
                    album = a.album,
                    artists = a.artists,
                    artwork = Artwork(ArtworkKind.RELEASE, "https://art/b", null),
                )
        val c =
            Fixtures.track("c", sourceId = "c")
                .copy(
                    album = AlbumRef("Same", SourceRef("yt", "album", "c")),
                    artists = listOf(ArtistRef("Same", SourceRef("yt", "artist", "c"))),
                )
        val (albums, artists) = CollectionGrouping.build(listOf(a, b, c, a))
        assertEquals(2, albums.size)
        assertEquals(2, artists.size)
        assertEquals(listOf(a.id, b.id), albums.first().tracks.map { it.id })
        assertEquals(b.artwork, albums.first().artwork)
    }

    @Test
    fun unknownSourcesRemainScopedToTrackAndArtistOccurrence() {
        val a =
            Fixtures.track("a", sourceId = "a")
                .copy(album = AlbumRef("Same", null), artists = listOf(ArtistRef("Same", null)))
        val b = Fixtures.track("b", sourceId = "b").copy(album = a.album, artists = a.artists)
        val groups = CollectionGrouping.build(listOf(a, b))
        assertEquals(2, groups.first.size)
        assertEquals(2, groups.second.size)
        assertNotEquals(CollectionGrouping.artistKey(a, 0), CollectionGrouping.artistKey(b, 0))
        assertEquals(CollectionGrouping.albumKey(a), groups.first.first().key)
    }
}
