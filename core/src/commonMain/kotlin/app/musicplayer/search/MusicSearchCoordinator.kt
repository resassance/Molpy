package app.musicplayer.search

import app.musicplayer.catalog.*
import app.musicplayer.model.*

class MusicSearchCoordinator(
    private val catalog: MusicSearchPort,
    private val tracks: SearchCoordinator,
) : MusicSearchPort {
    override suspend fun search(query: String, filter: SearchFilter) = search(query, filter) {}

    suspend fun search(
        query: String,
        filter: SearchFilter,
        onResults: (SearchPage) -> Unit,
    ): AppResult<SearchPage> {
        if (query.isBlank()) return AppResult.Success(SearchPage(null, emptyList(), null))
        val primary =
            when (val result = catalog.search(query.trim(), filter)) {
                is AppResult.Success -> AppResult.Success(displayPage(result.value))
                is AppResult.Failure -> result
            }
        if (filter != SearchFilter.ALL && filter != SearchFilter.TRACKS)
            return primary.also { it.valueOrNull()?.let(onResults) }
        val page = primary.valueOrNull()
        if (filter == SearchFilter.ALL && page != null && page.items().isNotEmpty()) {
            onResults(page)
            if (page.tracks().isEmpty()) return primary
            return when (
                val result =
                    tracks.enrich(query, page.tracks(), false) {
                        onResults(page.withTracks(it.tracks))
                    }
            ) {
                is AppResult.Success -> AppResult.Success(page.withTracks(result.value.tracks))
                is AppResult.Failure -> primary
            }
        }
        val seed =
            if (filter == SearchFilter.TRACKS)
                when (primary) {
                    is AppResult.Success -> AppResult.Success(primary.value.tracks())
                    is AppResult.Failure -> primary
                }
            else null
        fun normalized(result: SearchResults): SearchPage {
            val original = page?.tracks().orEmpty().map { it.id }.toSet()
            val keepsCursor =
                !result.fromFallback &&
                    result.tracks.isNotEmpty() &&
                    result.tracks.all { it.id in original } &&
                    result.primaryFailure == null
            return SearchPage(
                null,
                listOf(
                    SearchSection(
                        SearchFilter.TRACKS,
                        result.tracks.mapNotNull { track ->
                            track.candidates.firstOrNull()?.source?.let {
                                SearchItem.TrackItem(track, it)
                            }
                        },
                    )
                ),
                if (keepsCursor) page?.next else null,
                result.primaryFailure ?: primary.errorOrNull() ?: page?.primaryFailure,
            )
        }
        val result =
            if (seed == null) tracks.search(query) { onResults(normalized(it)) }
            else tracks.search(query, seed) { onResults(normalized(it)) }
        return when (result) {
            is AppResult.Success -> AppResult.Success(normalized(result.value))
            is AppResult.Failure -> result
        }
    }

    override suspend fun more(cursor: CatalogCursor): AppResult<SearchPage> =
        when (val result = catalog.more(cursor)) {
            is AppResult.Success -> AppResult.Success(displayPage(result.value))
            is AppResult.Failure -> result
        }

    suspend fun more(
        cursor: CatalogCursor,
        query: String,
        filter: SearchFilter,
        current: List<Track>,
        onResults: (SearchPage) -> Unit = {},
    ): AppResult<SearchPage> =
        when (val result = more(cursor)) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                var page = result.value
                if (filter == SearchFilter.TRACKS) {
                    val accepted =
                        tracks
                            .filterContinuation(query, current, page.tracks())
                            .map { it.id }
                            .toSet()
                    fun keep(item: SearchItem) =
                        item !is SearchItem.TrackItem || item.track.id in accepted
                    page =
                        page.copy(
                            topResult = page.topResult?.takeIf(::keep),
                            sections =
                                page.sections.map { it.copy(items = it.items.filter(::keep)) },
                        )
                }
                onResults(page)
                if (page.tracks().isEmpty()) AppResult.Success(page)
                else
                    when (
                        val enriched =
                            tracks.enrich(query, page.tracks(), false) {
                                onResults(page.withTracks(it.tracks))
                            }
                    ) {
                        is AppResult.Success ->
                            AppResult.Success(page.withTracks(enriched.value.tracks))
                        is AppResult.Failure -> AppResult.Success(page)
                    }
            }
        }

    private fun displayPage(page: SearchPage): SearchPage =
        page.withTracks(page.tracks().map(tracks::displayTrack))
}
