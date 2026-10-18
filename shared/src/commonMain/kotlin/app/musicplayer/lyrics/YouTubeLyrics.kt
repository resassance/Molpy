package app.musicplayer.lyrics

import app.musicplayer.model.*
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.response.BrowseResponse
import com.metrolist.innertubex.models.response.NextResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.*

/** Plain description-shelf lyrics only. The pinned library has no verified timed-lyrics schema. */
class YouTubeLyrics(private val client: InnerTube) : LyricsPort {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetch(track: Track, candidate: PlaybackCandidate): AppResult<LyricsCandidate?> {
        if (candidate.source.provider != "youtube" || candidate.source.kind != "video") return AppResult.Success(null)
        val anonymous = client.createIsolatedSession(includeAccount = false)
        return try {
            val next = anonymous.next(YouTubeClient.WEB_REMIX, videoId = candidate.source.id).bodyAsText()
            val renderer = decodeNext(next) ?: return changed()
            val tabs = renderer.obj("tabbedRenderer")?.obj("watchNextTabbedResultsRenderer")?.get("tabs") as? JsonArray
                ?: return changed()
            // Maintained Metrolist's adapter uses watch-next tab index 1, independent of translated labels.
            val tab = (tabs.getOrNull(1) as? JsonObject)?.obj("tabRenderer") ?: return changed()
            if (tab["endpoint"] == JsonNull) return AppResult.Success(null)
            val endpoint = tab.obj("endpoint")?.obj("browseEndpoint") ?: return changed()
            val browseId = endpoint.string("browseId")?.takeIf { it.isNotBlank() } ?: return changed()
            val browse = anonymous.browse(YouTubeClient.WEB_REMIX, browseId = browseId, params = endpoint.string("params"), setLogin = false).bodyAsText()
            val section = decodeBrowse(browse) ?: return changed()
            val contents = section["contents"] as? JsonArray ?: return changed()
            val shelf = contents.firstNotNullOfOrNull { (it as? JsonObject)?.obj("musicDescriptionShelfRenderer") } ?: return changed()
            val runs = shelf.obj("description")?.get("runs") as? JsonArray ?: return changed()
            val text = buildString {
                for (run in runs) append((run as? JsonObject)?.string("text") ?: return changed())
            }
            if (text.isBlank()) AppResult.Success(null)
            else AppResult.Success(LyricsCandidate(
                NormalizedLyrics(text, emptyList(), LyricsKind.PLAIN, SourceRef("youtube_music", "lyrics", browseId)),
                versionMatched = true, timingValid = false,
            ))
        } catch (e: Exception) {
            lyricsFailure(e, "lyrics.youtube.fetch")
        } finally {
            // Only the adapter-created isolated session is owned here; it retains the caller-owned HTTP client.
            anonymous.close()
        }
    }

    private fun decodeNext(body: String): JsonObject? = try {
        json.decodeFromString<NextResponse>(body).contents?.singleColumnMusicWatchNextResultsRenderer
    } catch (_: IllegalArgumentException) { null }

    private fun decodeBrowse(body: String): JsonObject? = try {
        json.decodeFromString<BrowseResponse>(body).contents?.sectionListRenderer
    } catch (_: IllegalArgumentException) { null }

    private fun changed() = AppResult.Failure(AppError(ErrorCode.PROVIDER_CHANGED, "lyrics.youtube.schema", false))
    private fun JsonObject.obj(name: String) = this[name] as? JsonObject
    private fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
