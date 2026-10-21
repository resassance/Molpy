package app.musicplayer.search

import app.musicplayer.catalog.CatalogPort
import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

data class SearchResults(val tracks: List<Track>, val primaryFailure: AppError? = null)

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
        val strictIntent = SearchIntent(trimmed, normalizer.versionMarkers(trimmed))
        val intent = SearchIntent(trimmed, normalizer.queryVersionMarkers(trimmed))
        val primary = catalog.searchSongs(trimmed)
        if (primary is AppResult.Success) {
            val suitable =
                primary.value
                    .filter { suitable(it, strictIntent) }
                    .ifEmpty {
                        primary.value.filter { suitable(it, intent) }
                    }
            if (suitable.isNotEmpty()) return AppResult.Success(SearchResults(suitable))
        }
        val fallback = catalog.searchVideos(trimmed)
        if (fallback is AppResult.Failure)
            return AppResult.Failure(primary.errorOrNull() ?: fallback.error)
        fallback as AppResult.Success
        var primaryFailure = primary.errorOrNull()
        val tracks = fallback.value.distinctBy { it.id }.map(::parseTentative).toMutableList()
        onResults(SearchResults(tracks.toList(), primaryFailure))
        val updates = Mutex()
        val requests = Semaphore(3)
        // Enrichment improves visible results; a slow metadata provider must not hold the list
        // hostage.
        withTimeoutOrNull(3_000) {
            tracks
                .toList()
                .mapIndexed { index, partial ->
                    async {
                        requests.withPermit {
                            val matches = catalog.findMusicMatches(partial)
                            val metadata =
                                if (matches is AppResult.Success)
                                    matcher
                                        .rank(partial, matches.value, intent)
                                        .singleOrNull { it.autoLink }
                                        ?.track
                                else null
                            updates.withLock {
                                ensureActive()
                                if (
                                    primaryFailure == null &&
                                        matches.errorOrNull()?.code == ErrorCode.DATABASE
                                )
                                    primaryFailure = matches.errorOrNull()
                                if (metadata != null)
                                    tracks[index] =
                                        partial.copy(
                                            title = metadata.title,
                                            artists = metadata.artists,
                                            album = metadata.album ?: partial.album,
                                            artwork = metadata.artwork,
                                            durationMs = metadata.durationMs,
                                            provenance = metadata.provenance,
                                        )
                                onResults(SearchResults(tracks.toList(), primaryFailure))
                            }
                        }
                    }
                }
                .awaitAll()
        }
        return AppResult.Success(SearchResults(tracks.toList(), primaryFailure))
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
