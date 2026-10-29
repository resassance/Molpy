package app.musicplayer.ui.search

import app.musicplayer.catalog.*

internal fun SearchPage.displaySections(filter: SearchFilter): List<SearchSection> {
    if (filter != SearchFilter.ALL) {
        val matches = items().filter { it.filter == filter }
        return if (matches.isEmpty()) emptyList() else listOf(SearchSection(filter, matches))
    }
    val seen = mutableSetOf<String>()
    topResult?.let { seen += it.key }
    val groups = sections.flatMap { it.items }.filter { seen.add(it.key) }.groupBy { it.filter }
    return listOf(
            SearchFilter.ALBUMS,
            SearchFilter.PLAYLISTS,
            SearchFilter.TRACKS,
            SearchFilter.ARTISTS,
        )
        .mapNotNull { category ->
            groups[category]?.takeIf { it.isNotEmpty() }?.let { SearchSection(category, it) }
        }
}
