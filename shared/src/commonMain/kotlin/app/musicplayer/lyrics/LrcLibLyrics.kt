package app.musicplayer.lyrics

import app.musicplayer.search.TrackNormalizer
import app.musicplayer.model.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/** LRCLIB metadata matching is checked locally against the selected recording before promotion. */
class LrcLibLyrics(private val client: HttpClient) : LyricsPort {
    private val json = Json { ignoreUnknownKeys = true }
    private val normalizer = TrackNormalizer()
    private val parser = LrcParser()

    override suspend fun fetch(track: Track, candidate: PlaybackCandidate): AppResult<LyricsCandidate?> {
        if (track.title.isBlank() || track.artists.isEmpty()) return AppResult.Failure(AppError(ErrorCode.INVALID_CONFIG, "lyrics.lrclib.fetch", false))
        return try {
            val selectedDuration = candidate.durationMs ?: track.durationMs
            val response = client.get("https://lrclib.net/api/get") {
                parameter("track_name", track.title)
                parameter("artist_name", track.artists.joinToString(", ") { it.name })
                track.album?.name?.takeIf { it.isNotBlank() }?.let { parameter("album_name", it) }
                selectedDuration?.takeIf { it > 0 }?.let {
                    parameter("duration", if (it % 1000 == 0L) (it / 1000).toString() else (it / 1000.0).toString())
                }
            }
            if (response.status.value == 404) return AppResult.Success(null)
            if (response.status.value !in 200..299) return lyricsHttpFailure(response.status.value, "lyrics.lrclib.fetch")
            val data = try { json.decodeFromString<Response>(response.bodyAsText()) }
                catch (_: IllegalArgumentException) { return changed() }
            if (data.id < 0 || data.trackName.isNullOrBlank() || data.artistName.isNullOrBlank()) return changed()
            if (!matches(track, candidate, data)) return AppResult.Success(null)
            val source = SourceRef("lrclib", "lyrics", data.id.toString())
            if (data.instrumental) return AppResult.Success(LyricsCandidate(
                NormalizedLyrics(null, emptyList(), LyricsKind.INSTRUMENTAL, source), true, false,
            ))
            val syncedText = data.syncedLyrics?.takeIf { it.isNotBlank() }
            val lines = syncedText?.let { parser.parse(it, selectedDuration).valueOrNull() }
            val plain = data.plainLyrics?.takeIf { it.isNotBlank() }
                ?: lines?.joinToString("\n") { it.text }?.takeIf { it.isNotBlank() }
            val suitableTiming = lines != null && selectedDuration != null && selectedDuration > 0 &&
                data.duration != null && durationMatches(selectedDuration, data.duration) &&
                hasCatalogIdentity(track, candidate) &&
                (track.durationMs?.let { durationMatches(it, data.duration) } ?: true)
            val normalized = when {
                suitableTiming -> NormalizedLyrics(plain, checkNotNull(lines), LyricsKind.LINE_SYNCED, source)
                plain != null -> NormalizedLyrics(plain, emptyList(), LyricsKind.PLAIN, source)
                else -> return if (syncedText != null) changed() else AppResult.Success(null)
            }
            AppResult.Success(LyricsCandidate(normalized, versionMatched = true, timingValid = suitableTiming))
        } catch (e: ResponseException) {
            if (e.response.status.value == 404) AppResult.Success(null) else lyricsFailure(e, "lyrics.lrclib.fetch")
        } catch (e: Exception) { lyricsFailure(e, "lyrics.lrclib.fetch") }
    }

    private fun matches(track: Track, candidate: PlaybackCandidate, data: Response): Boolean {
        if (!versionMatches(track, candidate, data)) return false
        val artists = track.artists.joinToString(", ") { it.name }
        if (normalizer.normalizeArtist(artists) != normalizer.normalizeArtist(checkNotNull(data.artistName))) return false
        val duration = candidate.durationMs ?: track.durationMs
        if (duration != null && (data.duration == null || !durationMatches(duration, data.duration))) return false
        val expectedAlbum = track.album?.name?.takeIf { it.isNotBlank() }
        if (expectedAlbum != null && !data.albumName.isNullOrBlank() &&
            normalizer.normalizeTitle(expectedAlbum) != normalizer.normalizeTitle(data.albumName)) return false
        return true
    }

    private fun versionMatches(track: Track, candidate: PlaybackCandidate, data: Response): Boolean {
        val version = candidate.version.trim()
        val rawMarkers = normalizer.versionMarkers(track.title)
        val explicitMarkers = normalizer.versionMarkers(version)
        val baseVersion = version.lowercase() in BASE_VERSIONS
        val expectedMarkers = when {
            version.isEmpty() -> {
                // CatalogMapper does not invent studio labels. A blank version needs affirmative catalog evidence.
                if (!hasBlankVersionEvidence(track, candidate, data)) return false
                rawMarkers
            }
            baseVersion -> {
                if (rawMarkers.isNotEmpty()) return false
                emptySet()
            }
            explicitMarkers.isEmpty() -> return false
            rawMarkers.isNotEmpty() && rawMarkers != explicitMarkers -> return false
            else -> explicitMarkers
        }
        if (expectedMarkers != normalizer.versionMarkers(checkNotNull(data.trackName))) return false
        val rawKey = normalizer.normalizeTitle(track.title)
        val providerKey = normalizer.normalizeTitle(data.trackName)
        if (version.isNotEmpty() && !baseVersion) {
            if (rawMarkers.isEmpty()) return normalizer.normalizeTitle("${track.title} ($version)") == providerKey
            // The canonical normalizer retains identifying years/venues, while generic labels normalize away.
            val detailKey = normalizer.normalizeTitle("($version)")
            if (detailKey.isNotBlank() && !rawKey.endsWith(" $detailKey")) return false
        }
        return rawKey == providerKey
    }

    private fun hasBlankVersionEvidence(track: Track, candidate: PlaybackCandidate, data: Response): Boolean {
        if (!hasCatalogIdentity(track, candidate)) return false
        val album = track.album?.name?.takeIf { it.isNotBlank() } ?: return false
        val providerAlbum = data.albumName?.takeIf { it.isNotBlank() } ?: return false
        if (normalizer.normalizeTitle(album) != normalizer.normalizeTitle(providerAlbum)) return false
        val catalogDuration = track.durationMs ?: return false
        val selectedDuration = candidate.durationMs ?: return false
        val providerDuration = data.duration ?: return false
        return durationMatches(catalogDuration, providerDuration) && durationMatches(selectedDuration, providerDuration)
    }

    private fun hasCatalogIdentity(track: Track, candidate: PlaybackCandidate) =
        candidate.confidence == MatchConfidence.HIGH && !track.provenance.tentative &&
            track.provenance.source.provider == "youtube_music" && track.provenance.source.kind == "song" &&
            track.provenance.source.id == candidate.source.id && candidate.source.provider == "youtube" && candidate.source.kind == "video"

    private fun durationMatches(milliseconds: Long, seconds: Double) = seconds.isFinite() && seconds > 0 && abs(milliseconds - seconds * 1000) <= 2000
    private fun changed() = AppResult.Failure(AppError(ErrorCode.PROVIDER_CHANGED, "lyrics.lrclib.schema", false))

    private companion object { val BASE_VERSIONS = setOf("studio", "original", "studio version", "original version") }

    @Serializable
    private data class Response(
        val id: Long,
        val trackName: String?,
        val artistName: String?,
        val albumName: String? = null,
        val duration: Double?,
        val instrumental: Boolean,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
    )
}
