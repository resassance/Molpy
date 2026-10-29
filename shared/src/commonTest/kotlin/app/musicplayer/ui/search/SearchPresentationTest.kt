package app.musicplayer.ui.search

import app.musicplayer.catalog.*
import app.musicplayer.model.*
import kotlin.test.*

class SearchPresentationTest {
    private val source = SearchFixture()
    private val playlist =
        SearchItem.PlaylistItem(
            PublicPlaylistCard(
                PublicPlaylistRef("Night", SourceRef("test", "playlist", "night")),
                artwork = null,
                owner = "Listener",
                count = null,
            )
        )

    @Test
    fun allGroupsAlbumsPlaylistsTracksArtists() {
        val page =
            SearchPage(
                null,
                listOf(
                    SearchSection(
                        SearchFilter.ALL,
                        listOf(source.a, source.artist, playlist, source.album, source.b),
                    )
                ),
                null,
            )
        val sections = page.displaySections(SearchFilter.ALL)
        assertEquals(
            listOf(
                SearchFilter.ALBUMS,
                SearchFilter.PLAYLISTS,
                SearchFilter.TRACKS,
                SearchFilter.ARTISTS,
            ),
            sections.map { it.filter },
        )
        assertEquals(listOf(source.a, source.b), sections[2].items)
        assertEquals(5, sections.sumOf { it.items.size })
    }

    @Test
    fun topResultAppearsOnlyOnceForEveryType() {
        for (top in listOf(source.a, source.artist, source.album, playlist)) {
            val page =
                SearchPage(
                    top,
                    listOf(
                        SearchSection(
                            SearchFilter.ALL,
                            listOf(top, source.b, top),
                        )
                    ),
                    null,
                )
            assertEquals(
                listOf(source.b),
                page.displaySections(SearchFilter.ALL).flatMap { it.items },
            )
        }
    }

    @Test
    fun mixedAndContinuedSectionsMergeWithoutLosingItems() {
        val page =
            SearchPage(
                source.artist,
                listOf(
                    SearchSection(SearchFilter.TRACKS, listOf(source.a, source.album)),
                    SearchSection(SearchFilter.ALBUMS, listOf(source.album, playlist)),
                    SearchSection(SearchFilter.TRACKS, listOf(source.b, source.a)),
                ),
                null,
            )
        val sections = page.displaySections(SearchFilter.ALL)
        assertEquals(
            listOf(SearchFilter.ALBUMS, SearchFilter.PLAYLISTS, SearchFilter.TRACKS),
            sections.map { it.filter },
        )
        assertEquals(
            listOf(source.album, playlist, source.a, source.b),
            sections.flatMap { it.items },
        )
        assertEquals(1, sections.count { it.filter == SearchFilter.TRACKS })
    }

    @Test
    fun filteredResultsKeepTopAndProviderOrder() {
        val page =
            SearchPage(
                source.b,
                listOf(
                    SearchSection(
                        SearchFilter.ALL,
                        listOf(source.artist, source.a, source.b),
                    )
                ),
                null,
            )
        val sections = page.displaySections(SearchFilter.TRACKS)
        assertEquals(
            listOf(SearchSection(SearchFilter.TRACKS, listOf(source.b, source.a))),
            sections,
        )
    }

    @Test
    fun emptySectionsAreOmitted() {
        assertTrue(SearchPage(null, emptyList(), null).displaySections(SearchFilter.ALL).isEmpty())
        assertTrue(page(source.artist).displaySections(SearchFilter.ALBUMS).isEmpty())
        assertTrue(page(source.artist).displaySections(SearchFilter.ALL).isEmpty())
    }

    @Test
    fun presentationDoesNotMutatePlaybackOrder() {
        val page =
            SearchPage(
                source.b,
                listOf(
                    SearchSection(
                        SearchFilter.ALL,
                        listOf(source.artist, source.a, source.album, playlist),
                    )
                ),
                null,
            )
        val before = page.copy()
        page.displaySections(SearchFilter.ALL)
        assertEquals(listOf(source.b.track, source.a.track), page.tracks())
        assertEquals(before, page)
    }
}
