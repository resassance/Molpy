package app.musicplayer.catalog

import app.musicplayer.model.*
import com.metrolist.innertubex.models.MusicResponsiveListItemRenderer
import com.metrolist.innertubex.models.MusicShelfRenderer
import com.metrolist.innertubex.models.response.SearchResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.time.Clock

/** Tagged DTOs plus private traversal for fields the upstream models deliberately leave as JSON. */
internal class CatalogMapper(
    private val identities: IdentityStore,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun mapSongs(body: String): AppResult<List<Track>> = normalize("catalog.music.map") {
        val response = json.decodeFromString<SearchResponse>(body)
        val contents = response.contents ?: schemaFailure()
        val section = contents.sectionListRenderer ?: contents.tabbedSearchResultsRenderer?.let { tabbed ->
            val tabs = tabbed.requiredArray("tabs").map { it.requiredObject().requiredObject("tabRenderer") }
            val tab = tabs.firstOrNull { it["selected"]?.jsonPrimitive?.booleanOrNull == true }
                ?: tabs.firstOrNull { it["content"] is JsonObject } ?: schemaFailure()
            tab.requiredObject("content").requiredObject("sectionListRenderer")
        } ?: schemaFailure()
        val tracks = mutableListOf<Track>()
        for (sectionItem in section.requiredArray("contents")) {
            val item = sectionItem.requiredObject()
            when {
                item["musicShelfRenderer"] != null -> {
                    val rawShelf = item.requiredObject("musicShelfRenderer")
                    // Compile and consume the actual v0.10.0 shelf/row models, not look-alike DTOs.
                    val shelf = json.decodeFromJsonElement<MusicShelfRenderer>(rawShelf)
                    val rows = shelf.contents ?: schemaFailure()
                    val rawRows = rawShelf.requiredArray("contents")
                    rows.forEachIndexed { index, row ->
                        val actual = row.musicResponsiveListItemRenderer ?: schemaFailure()
                        val raw = rawRows[index].requiredObject().requiredObject("musicResponsiveListItemRenderer")
                        musicTrack(actual, raw)?.let(tracks::add)
                    }
                }
                else -> schemaFailure()
            }
        }
        tracks.distinctBy { it.id }
    }

    suspend fun mapVideos(body: String): AppResult<List<Track>> = normalize("catalog.video.map") {
        // v0.10.0 SearchResponse has no twoColumnSearchResultsRenderer/VideoRenderer DTO.
        val root = json.parseToJsonElement(body).requiredObject()
        val section = root.requiredObject("contents").requiredObject("twoColumnSearchResultsRenderer")
            .requiredObject("primaryContents").requiredObject("sectionListRenderer")
        val tracks = mutableListOf<Track>()
        for (sectionItem in section.requiredArray("contents")) {
            val item = sectionItem.requiredObject()
            when {
                item["itemSectionRenderer"] != null -> {
                    for (entry in item.requiredObject("itemSectionRenderer").requiredArray("contents")) {
                        val renderer = entry.requiredObject()
                        when {
                            renderer["videoRenderer"] != null -> tracks.add(videoTrack(renderer.requiredObject("videoRenderer")))
                            renderer.keys.any { it in setOf("channelRenderer", "playlistRenderer") } -> Unit
                            else -> schemaFailure()
                        }
                    }
                }
                item["continuationItemRenderer"] != null -> Unit
                else -> schemaFailure()
            }
        }
        tracks.distinctBy { it.id }
    }

    private suspend fun musicTrack(row: MusicResponsiveListItemRenderer, raw: JsonObject): Track? {
        val videoId = row.playlistItemData?.videoId
            ?: row.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs
                ?.firstOrNull()?.navigationEndpoint?.watchEndpoint?.videoId
            ?: row.navigationEndpoint?.watchEndpoint?.videoId
        if (videoId == null) {
            if (raw.objectOrNull("navigationEndpoint")?.browsePageType() in NON_TRACK_PAGE_TYPES) return null
            schemaFailure()
        }
        if (videoId.isBlank()) schemaFailure()
        val title = row.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs
            ?.joinToString("") { it.text }?.trim()?.takeIf { it.isNotBlank() } ?: schemaFailure()
        val rawColumns = raw.requiredArray("flexColumns")
        val relationships = rawColumns.drop(1).flatMap { column ->
            column.requiredObject().requiredObject("musicResponsiveListItemFlexColumnRenderer")
                .objectOrNull("text")?.arrayOrNull("runs")?.map { it.requiredObject() }.orEmpty()
        }
        val artists = relationships.filter { it.objectOrNull("navigationEndpoint")?.browsePageType() == ARTIST_PAGE }
            .map { run ->
                ArtistRef(run.requiredString("text"), SourceRef(PROVIDER, "artist", run.requiredObject("navigationEndpoint").requiredObject("browseEndpoint").requiredString("browseId")))
            }.distinct()
        val albumRun = relationships.firstOrNull { it.objectOrNull("navigationEndpoint")?.browsePageType() == ALBUM_PAGE }
        val album = albumRun?.let { run ->
            AlbumRef(run.requiredString("text"), SourceRef(PROVIDER, "album", run.requiredObject("navigationEndpoint").requiredObject("browseEndpoint").requiredString("browseId")))
        }
        val duration = relationships.filter { it["navigationEndpoint"] == null }
            .mapNotNull { it.stringOrNull("text")?.let(::durationMs) }.lastOrNull()
        val thumbnail = row.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails
            ?.maxByOrNull { (it.width ?: 0).toLong() * (it.height ?: 0) }?.url?.takeIf { it.isNotBlank() }
        val titleEndpoint = rawColumns.firstOrNull()?.requiredObject()
            ?.requiredObject("musicResponsiveListItemFlexColumnRenderer")?.objectOrNull("text")
            ?.arrayOrNull("runs")?.firstOrNull()?.requiredObject()?.objectOrNull("navigationEndpoint")
        val videoType = (titleEndpoint ?: raw.objectOrNull("navigationEndpoint"))
            ?.objectOrNull("watchEndpoint")?.objectOrNull("watchEndpointMusicSupportedConfigs")
            ?.objectOrNull("watchEndpointMusicConfig")?.stringOrNull("musicVideoType")
        val artwork = Artwork(
            when { thumbnail == null -> ArtworkKind.PLACEHOLDER
                videoType == "MUSIC_VIDEO_TYPE_ATV" -> ArtworkKind.RELEASE
                else -> ArtworkKind.VIDEO }, thumbnail, null,
        )
        return track(videoId, title, artists, album, artwork, duration, tentative = false)
    }

    private suspend fun videoTrack(row: JsonObject): Track {
        val videoId = row.requiredString("videoId")
        val title = text(row.requiredObject("title"))?.takeIf { it.isNotBlank() } ?: schemaFailure()
        // Upload channels are display credits, never silently promoted to music artist identities.
        val owner = row.objectOrNull("ownerText") ?: row.objectOrNull("longBylineText")
        val artists = owner?.let(::text)?.takeIf { it.isNotBlank() }?.let { listOf(ArtistRef(it, null)) }.orEmpty()
        val thumbnail = row.objectOrNull("thumbnail")?.arrayOrNull("thumbnails")
            ?.map { it.requiredObject() }?.maxByOrNull { (it["width"]?.jsonPrimitive?.intOrNull ?: 0).toLong() * (it["height"]?.jsonPrimitive?.intOrNull ?: 0) }
            ?.stringOrNull("url")?.takeIf { it.isNotBlank() }
        return track(
            videoId, title, artists, null,
            Artwork(if (thumbnail == null) ArtworkKind.PLACEHOLDER else ArtworkKind.VIDEO, thumbnail, null),
            row.objectOrNull("lengthText")?.let(::text)?.let(::durationMs), tentative = true,
        )
    }

    private suspend fun track(
        videoId: String, title: String, artists: List<ArtistRef>, album: AlbumRef?,
        artwork: Artwork, duration: Long?, tentative: Boolean,
    ): Track {
        val source = SourceRef(PROVIDER, "video", videoId)
        return Track(
            identities.getOrCreate(source), title, artists, album, artwork, duration, setOf(source),
            MetadataProvenance(if (tentative) source else SourceRef("youtube_music", "song", videoId), nowMs(), tentative),
            listOf(PlaybackCandidate(CandidateId("youtube:video:$videoId"), source, "", duration, if (tentative) MatchConfidence.TENTATIVE else MatchConfidence.HIGH)),
        )
    }

    private suspend fun normalize(operation: String, block: suspend () -> List<Track>): AppResult<List<Track>> = try {
        AppResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: AppFailureException) {
        AppResult.Failure(e.error)
    } catch (_: SerializationException) {
        failure(operation)
    } catch (_: IllegalArgumentException) {
        failure(operation)
    } catch (_: SchemaException) {
        failure(operation)
    }

    private fun failure(operation: String) = AppResult.Failure(AppError(ErrorCode.PROVIDER_CHANGED, operation, false))
    private fun JsonElement.requiredObject() = this as? JsonObject ?: schemaFailure()
    private fun JsonObject.requiredObject(key: String) = this[key]?.requiredObject() ?: schemaFailure()
    private fun JsonObject.objectOrNull(key: String) = this[key] as? JsonObject
    private fun JsonObject.arrayOrNull(key: String) = this[key] as? JsonArray
    private fun JsonObject.requiredArray(key: String) = arrayOrNull(key) ?: schemaFailure()
    private fun JsonObject.stringOrNull(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.requiredString(key: String) = stringOrNull(key)?.takeIf { it.isNotBlank() } ?: schemaFailure()
    private fun JsonObject.browsePageType() = objectOrNull("browseEndpoint")?.objectOrNull("browseEndpointContextSupportedConfigs")
        ?.objectOrNull("browseEndpointContextMusicConfig")?.stringOrNull("pageType")
    private fun text(value: JsonObject): String? = value.stringOrNull("simpleText")
        ?: value.arrayOrNull("runs")?.joinToString("") { it.requiredObject().requiredString("text") }
    private fun durationMs(value: String): Long? {
        val parts = value.trim().split(':')
        if (parts.size !in 2..3 || parts.any { it.isEmpty() || it.any { char -> !char.isDigit() } }) return null
        val numbers = parts.map { it.toLongOrNull() ?: return null }
        if (numbers.drop(1).any { it !in 0..59 } || numbers.first() > 999) return null
        return numbers.fold(0L) { total, number -> total * 60 + number } * 1000
    }
    private fun schemaFailure(): Nothing = throw SchemaException()
    private class SchemaException : Exception()

    private companion object {
        const val PROVIDER = "youtube"
        const val ARTIST_PAGE = "MUSIC_PAGE_TYPE_ARTIST"
        const val ALBUM_PAGE = "MUSIC_PAGE_TYPE_ALBUM"
        val NON_TRACK_PAGE_TYPES = setOf(ARTIST_PAGE, ALBUM_PAGE, "MUSIC_PAGE_TYPE_PLAYLIST")
    }
}
