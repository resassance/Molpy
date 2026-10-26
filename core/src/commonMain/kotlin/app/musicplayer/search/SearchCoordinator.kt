package app.musicplayer.search

import app.musicplayer.catalog.CatalogPort
import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

data class SearchResults(
    val tracks: List<Track>,
    val primaryFailure: AppError? = null,
    val fromFallback: Boolean = false,
)

class SearchCoordinator(
    private val catalog: CatalogPort,
    private val matcher: TrackMatcher = TrackMatcher(),
) {
    private val normalizer = TrackNormalizer()

    suspend fun search(
        query: String,
        onResults: (SearchResults) -> Unit = {},
    ): AppResult<SearchResults> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return AppResult.Success(SearchResults(emptyList()))
        return search(trimmed, catalog.searchSongs(trimmed), onResults)
    }

    suspend fun search(
        query: String,
        primary: AppResult<List<Track>>,
        onResults: (SearchResults) -> Unit = {},
    ): AppResult<SearchResults> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return AppResult.Success(SearchResults(emptyList()))
        val strictIntent = SearchIntent(trimmed, normalizer.versionMarkers(trimmed))
        val intent = SearchIntent(trimmed, normalizer.queryVersionMarkers(trimmed))
        if (primary is AppResult.Success) {
            val suitable =
                primary.value
                    .filter { suitable(it, strictIntent) }
                    .ifEmpty {
                        primary.value.filter { suitable(it, intent) }
                    }
            if (suitable.isNotEmpty()) return complete(suitable, null, intent, false, onResults)
        }
        val fallback = catalog.searchVideos(trimmed)
        if (fallback is AppResult.Failure)
            return AppResult.Failure(primary.errorOrNull() ?: fallback.error)
        fallback as AppResult.Success
        return complete(
            fallback.value.distinctBy { it.id }.map(::parseTentative),
            primary.errorOrNull(),
            intent,
            true,
            onResults,
        )
    }

    suspend fun enrich(
        query: String,
        initial: List<Track>,
        allowMetadataMatching: Boolean,
        onResults: (SearchResults) -> Unit = {},
    ): AppResult<SearchResults> =
        complete(
            initial,
            null,
            SearchIntent(query, normalizer.queryVersionMarkers(query)),
            allowMetadataMatching,
            onResults,
            fromFallback = false,
        )

    private suspend fun complete(
        initial: List<Track>,
        initialFailure: AppError?,
        intent: SearchIntent,
        enrich: Boolean,
        onResults: (SearchResults) -> Unit,
        fromFallback: Boolean = enrich,
    ): AppResult<SearchResults> {
        val tracks = initial.toMutableList()
        var primaryFailure = initialFailure
        onResults(SearchResults(tracks.toList(), primaryFailure, fromFallback))
        val updates = Mutex()
        val requests = Semaphore(3)
        val matchesByIndex = mutableMapOf<Int, List<Track>>()
        fun applyMetadata(index: Int) {
            val latest = tracks[index]
            val metadata =
                matcher
                    .rank(latest, matchesByIndex[index].orEmpty(), intent)
                    .singleOrNull { it.autoLink }
                    ?.track ?: return
            tracks[index] =
                latest.copy(
                    title = metadata.title,
                    artists = metadata.artists,
                    album = metadata.album ?: latest.album,
                    artwork = metadata.artwork,
                    durationMs = latest.durationMs?.takeIf { it > 0 } ?: metadata.durationMs,
                    provenance = metadata.provenance,
                )
        }
        withTimeoutOrNull(3_000) {
            val durationJob = async {
                val missing =
                    initial
                        .filter { it.durationMs == null || it.durationMs <= 0 }
                        .mapNotNull { it.candidates.firstOrNull()?.source }
                        .distinct()
                if (missing.isNotEmpty()) {
                    val durations = catalog.durations(missing).valueOrNull().orEmpty()
                    updates.withLock {
                        ensureActive()
                        for (index in tracks.indices) {
                            var track = tracks[index]
                            durations.forEach { (source, duration) ->
                                track = track.withMissingDuration(source, duration)
                            }
                            tracks[index] = track
                            applyMetadata(index)
                        }
                        onResults(SearchResults(tracks.toList(), primaryFailure, fromFallback))
                    }
                }
            }
            val matches =
                if (!enrich) emptyList()
                else
                    initial.mapIndexed { index, partial ->
                        async {
                            requests.withPermit {
                                val matches = catalog.findMusicMatches(partial)
                                updates.withLock {
                                    ensureActive()
                                    if (
                                        primaryFailure == null &&
                                            matches.errorOrNull()?.code == ErrorCode.DATABASE
                                    )
                                        primaryFailure = matches.errorOrNull()
                                    if (matches is AppResult.Success) {
                                        matchesByIndex[index] = matches.value
                                        applyMetadata(index)
                                    }
                                    onResults(
                                        SearchResults(tracks.toList(), primaryFailure, fromFallback)
                                    )
                                }
                            }
                        }
                    }
            (matches + durationJob).awaitAll()
        }
        return AppResult.Success(SearchResults(tracks.toList(), primaryFailure, fromFallback))
    }

    private fun suitable(track: Track, intent: SearchIntent): Boolean {
        if (!matcher.knownVersions(track)) return false
        val title = normalizer.normalizeTitle(track.title)
        if (title.isEmpty() || track.artists.none { it.name.isNotBlank() }) return false
        val versions =
            normalizer.versionMarkers(track.title) +
                track.candidates.flatMap { normalizer.explicitVersionMarkers(it.version) }
        if (versions != intent.requestedVersions) return false
        val queryTitle =
            if (intent.requestedVersions == normalizer.versionMarkers(intent.query))
                normalizer.normalizeTitle(intent.query)
            else normalizer.normalizeQuery(intent.query)
        val queryTokens = queryTitle.split(' ').filter { it.isNotEmpty() }.toSet()
        val trackTokens =
            (title + " " + track.artists.joinToString(" ") { normalizer.normalizeArtist(it.name) })
                .split(' ')
                .toSet()
        return queryTokens.isNotEmpty() && queryTokens.all { it in trackTokens }
    }

    internal fun filterContinuation(query: String, initial: List<Track>, next: List<Track>): List<Track> {
        val trimmed = query.trim()
        val strict = SearchIntent(trimmed, normalizer.versionMarkers(trimmed))
        val intent =
            if (initial.any { suitable(it, strict) }) strict
            else SearchIntent(trimmed, normalizer.queryVersionMarkers(trimmed))
        return next.filter { suitable(it, intent) }
    }

    internal fun displayTrack(track: Track): Track {
        if (track.provenance.tentative) return parseTentative(track)
        if (track.artwork.kind != ArtworkKind.VIDEO) return track
        var title = normalizer.cleanDisplayTitle(track.title)
        ARTIST_TITLE.find(title)?.let { delimiter ->
            val prefix = normalizer.normalizeArtist(title.substring(0, delimiter.range.first))
            val song = title.substring(delimiter.range.last + 1).trim()
            if (
                song.isNotEmpty() &&
                    track.artists.any { normalizer.normalizeArtist(it.name) == prefix }
            )
                title = song
        }
        return track.copy(title = title.ifBlank { track.title })
    }

    private fun parseTentative(track: Track): Track {
        if (!track.provenance.tentative) return track
        val title = normalizer.cleanDisplayTitle(track.title)
        val cleaned =
            track.copy(
                title = title,
                artists = track.artists.filter { it.source?.kind == "artist" },
            )
        val delimiter = ARTIST_TITLE.find(title)
        if (delimiter == null) return cleaned
        val artist = title.substring(0, delimiter.range.first).trim()
        val song = title.substring(delimiter.range.last + 1).trim()
        return if (artist.isEmpty() || song.isEmpty()) cleaned
        else track.copy(title = song, artists = listOf(ArtistRef(artist, null)))
    }

    private companion object {
        val ARTIST_TITLE = Regex("\\s+[-–—]\\s+")
    }
}
