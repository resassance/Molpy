package app.musicplayer.catalog

import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class BrowseMapperTest {
    private val album = AlbumRef("Meteora", SourceRef("youtube", "album", "album-one"))
    private val artist = ArtistRef("Linkin Park", SourceRef("youtube", "artist", "artist-one"))

    private fun fixture(name: String) =
        requireNotNull(javaClass.getResource("/catalog/browse/$name.json")).readText()

    @Test
    fun albumRowsShareSearchIdentityAndInheritReleaseMetadataAndFixedDuration() = runTest {
        val identities = SessionIdentityStore()
        val search =
            CatalogMapper(identities)
                .mapSongs(requireNotNull(javaClass.getResource("/catalog/song.json")).readText())
                .valueOrNull()!!
                .single()
        val mapped =
            BrowseMapper(identities) { 10 }.album(fixture("album-inline"), album).valueOrNull()!!
        val track = mapped.tracks.items.single()
        assertEquals(search.id, track.id)
        assertEquals(search.candidates.single().source, track.candidates.single().source)
        assertEquals("Meteora", track.album?.name)
        assertEquals(listOf(artist), track.artists)
        assertEquals(187000L, track.durationMs)
        assertEquals(ArtworkKind.RELEASE, track.artwork.kind)
        assertEquals("https://art.example/release.jpg", track.artwork.remoteUrl)
    }

    @Test
    fun explicitRowCreditsAreNotOverwrittenByAlbumHeader() = runTest {
        val mapped =
            BrowseMapper(SessionIdentityStore()) { 0 }
                .album(fixture("album-credits"), album)
                .valueOrNull()!!
        assertEquals("Linkin Park", mapped.tracks.items.single().artists.single().name)
    }

    @Test
    fun artistSectionsUseSourceEndpointsAndDoNotInventBrowseIds() = runTest {
        val mapped =
            BrowseMapper(SessionIdentityStore()) { 0 }
                .artist(fixture("artist"), artist)
                .valueOrNull()!!
        assertEquals(artist, mapped.artist)
        assertEquals("artist-songs", mapped.tracks.next?.browseId)
        assertEquals("songs-params", mapped.tracks.next?.params)
        assertEquals("album-one", mapped.albums.items.single().album.source?.id)
        assertEquals("single-one", mapped.singles.items.single().album.source?.id)
        assertEquals("artist-singles", mapped.singles.next?.browseId)
    }

    @Test
    fun linkedAlbumUsesCanonicalPlaylistAndDoesNotClaimNoTracks() = runTest {
        val mapped =
            BrowseMapper(SessionIdentityStore()) { 0 }
                .album(fixture("album-linked"), album)
                .valueOrNull()!!
        assertTrue(mapped.tracks.items.isEmpty())
        assertEquals("VLOLAK-fixture", mapped.tracks.next?.browseId)
    }

    @Test
    fun emptyUnavailableAndChangedSchemaAreDistinct() = runTest {
        val mapper = BrowseMapper(SessionIdentityStore()) { 0 }
        assertTrue(mapper.album(fixture("empty"), album).valueOrNull()!!.tracks.items.isEmpty())
        assertEquals(
            ErrorCode.PROVIDER_CHANGED,
            mapper.album(fixture("changed"), album).errorOrNull()?.code,
        )
        assertEquals(
            ErrorCode.SOURCE_UNAVAILABLE,
            mapper.artist(fixture("unavailable"), artist).errorOrNull()?.code,
        )
    }
}
