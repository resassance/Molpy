package app.musicplayer.search

import app.musicplayer.catalog.*
import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class MusicSearchDisplayTest {
    private fun decorated(title: String = "Linkin Park - Numb [Official Video] HD") =
        track(title = title)
            .copy(
                artists = listOf(ArtistRef("Linkin Park", SourceRef("youtube", "artist", "lp"))),
                artwork = Artwork(ArtworkKind.VIDEO, "https://images.invalid/video.jpg", null),
            )

    private fun page(track: Track) =
        SearchPage(
            null,
            listOf(
                SearchSection(
                    SearchFilter.TRACKS,
                    listOf(SearchItem.TrackItem(track, track.candidates.single().source)),
                )
            ),
            CatalogCursor("next"),
        )

    private fun raw(track: Track) =
        object : MusicSearchPort {
            override suspend fun search(query: String, filter: SearchFilter) =
                AppResult.Success(page(track))

            override suspend fun more(cursor: CatalogCursor) = AppResult.Success(page(track))
        }

    @Test
    fun decoratedMusicVideoIsCleanBeforeFirstPublicationWithoutMatchingFanout() = runTest {
        val original = decorated()
        val catalog = FakeCatalog()
        val observed = mutableListOf<SearchPage>()
        val result =
            MusicSearchCoordinator(raw(original), SearchCoordinator(catalog))
                .search("Numb", SearchFilter.ALL, observed::add)
                .valueOrNull()!!
        assertTrue(observed.isNotEmpty())
        assertTrue(observed.all { it.tracks().single().title == "Numb" })
        val clean = result.tracks().single()
        assertEquals(original.id, clean.id)
        assertEquals(original.artists, clean.artists)
        assertEquals(original.artwork, clean.artwork)
        assertEquals(original.provenance, clean.provenance)
        assertEquals(0, catalog.matchCalls + catalog.songCalls + catalog.videoCalls)
    }

    @Test
    fun continuationPreservesActualVersionAndSourcedArtistCredits() = runTest {
        val original = decorated("Linkin Park - Numb (Live) [Official Video]")
        val result =
            MusicSearchCoordinator(raw(original), SearchCoordinator(FakeCatalog()))
                .more(CatalogCursor("next"))
                .valueOrNull()!!
        assertEquals("Numb (Live)", result.tracks().single().title)
        assertEquals(original.artists, result.tracks().single().artists)
    }

    @Test
    fun unrelatedTitlePrefixIsNotGuessedToBeArtist() = runTest {
        val original = decorated("Part One - Numb [Official Video]")
        val result =
            MusicSearchCoordinator(raw(original), SearchCoordinator(FakeCatalog()))
                .more(CatalogCursor("next"))
                .valueOrNull()!!
        assertEquals("Part One - Numb", result.tracks().single().title)
    }
}
