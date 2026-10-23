package app.musicplayer.ui.search

import app.musicplayer.catalog.SearchFilter
import kotlin.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SearchNavigationTest {
    @Test
    fun returnRestoresFilterAndScroll() = runTest {
        val source = SearchFixture()
        val model = source.model(backgroundScope)
        model.search("Artist")
        runCurrent()
        model.updateScroll(4, 27)
        model.selectFilter(SearchFilter.ARTISTS)
        runCurrent()
        model.updateScroll(8, 19)
        model.selectFilter(SearchFilter.ALL)
        assertEquals(SearchScrollPosition(4, 27), model.state.value.scroll)
        model.selectFilter(SearchFilter.ARTISTS)
        assertEquals(SearchScrollPosition(8, 19), model.state.value.scroll)
        assertEquals(SearchFilter.ARTISTS, model.state.value.filter)
        assertEquals(2, source.calls.size)
        model.search("new")
        assertEquals(SearchScrollPosition(), model.state.value.scroll)
        model.close()
    }

    @Test
    fun disposedListCannotOverwriteAnotherFilterScroll() = runTest {
        val source = SearchFixture()
        val model = source.model(backgroundScope)
        model.search("Artist")
        runCurrent()
        model.selectFilter(SearchFilter.ARTISTS)
        model.updateScroll(7, 30, "Artist", SearchFilter.ALL)
        assertEquals(SearchScrollPosition(), model.state.value.scroll)
        model.close()
    }
}
