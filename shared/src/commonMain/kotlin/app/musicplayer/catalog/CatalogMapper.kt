package app.musicplayer.catalog

import app.musicplayer.model.*
import com.metrolist.innertubex.models.MusicShelfRenderer
import com.metrolist.innertubex.models.response.SearchResponse
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Tagged DTOs plus private traversal for fields the upstream models deliberately leave as JSON. */
internal class CatalogMapper(
    private val identities: IdentityStore,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val musicItems = MusicItemMapper(identities, nowMs)

    suspend fun mapSongs(body: String): AppResult<List<Track>> =
        normalize("catalog.music.map") {
            val response = json.decodeFromString<SearchResponse>(body)
            val contents = response.contents ?: schemaFailure()
            val section =
                contents.sectionListRenderer
                    ?: contents.tabbedSearchResultsRenderer?.let { tabbed ->
                        val tabs =
                            tabbed.requiredArray("tabs").map {
                                it.requiredObject().requiredObject("tabRenderer")
                            }
                        val tab =
                            tabs.firstOrNull {
                                it["selected"]?.jsonPrimitive?.booleanOrNull == true
                            } ?: tabs.firstOrNull { it["content"] is JsonObject } ?: schemaFailure()
                        tab.requiredObject("content").requiredObject("sectionListRenderer")
                    }
                    ?: schemaFailure()
            val tracks = mutableListOf<Track>()
            for (sectionItem in section.requiredArray("contents")) {
                val item = sectionItem.requiredObject()
                when {
                    item["musicShelfRenderer"] != null -> {
                        val rawShelf = item.requiredObject("musicShelfRenderer")
                        // Compile and consume the actual v0.10.0 shelf/row models, not look-alike
                        // DTOs.
                        val shelf = json.decodeFromJsonElement<MusicShelfRenderer>(rawShelf)
                        val rows = shelf.contents ?: schemaFailure()
                        val rawRows = rawShelf.requiredArray("contents")
                        rows.forEachIndexed { index, row ->
                            val actual = row.musicResponsiveListItemRenderer ?: schemaFailure()
                            val raw =
                                rawRows[index]
                                    .requiredObject()
                                    .requiredObject("musicResponsiveListItemRenderer")
                            musicItems.map(actual, raw)?.let(tracks::add)
                        }
                    }
                    else -> schemaFailure()
                }
            }
            tracks.distinctBy { it.id }
        }

    suspend fun mapVideos(body: String): AppResult<List<Track>> =
        normalize("catalog.video.map") {
            // v0.10.0 SearchResponse has no twoColumnSearchResultsRenderer/VideoRenderer DTO.
            val root = json.parseToJsonElement(body).requiredObject()
            val section =
                root
                    .requiredObject("contents")
                    .requiredObject("twoColumnSearchResultsRenderer")
                    .requiredObject("primaryContents")
                    .requiredObject("sectionListRenderer")
            val tracks = mutableListOf<Track>()
            for (sectionItem in section.requiredArray("contents")) {
                val item = sectionItem.requiredObject()
                when {
                    item["itemSectionRenderer"] != null -> {
                        for (entry in
                            item.requiredObject("itemSectionRenderer").requiredArray("contents")) {
                            val renderer = entry.requiredObject()
                            when {
                                renderer["videoRenderer"] != null ->
                                    tracks.add(videoTrack(renderer.requiredObject("videoRenderer")))
                                renderer.keys.any {
                                    it in setOf("channelRenderer", "playlistRenderer")
                                } -> Unit
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

    private suspend fun videoTrack(row: JsonObject): Track {
        val videoId = row.requiredString("videoId")
        val title = text(row.requiredObject("title"))?.takeIf { it.isNotBlank() } ?: schemaFailure()
        // Upload channels are display credits, never silently promoted to music artist identities.
        val owner = row.objectOrNull("ownerText") ?: row.objectOrNull("longBylineText")
        val artists =
            owner
                ?.let(::text)
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf(ArtistRef(it, null)) }
                .orEmpty()
        val thumbnail =
            row.objectOrNull("thumbnail")
                ?.arrayOrNull("thumbnails")
                ?.map { it.requiredObject() }
                ?.maxByOrNull {
                    (it["width"]?.jsonPrimitive?.intOrNull ?: 0).toLong() *
                        (it["height"]?.jsonPrimitive?.intOrNull ?: 0)
                }
                ?.stringOrNull("url")
                ?.takeIf { it.isNotBlank() }
        return musicItems.track(
            videoId,
            title,
            artists,
            null,
            Artwork(
                if (thumbnail == null) ArtworkKind.PLACEHOLDER else ArtworkKind.VIDEO,
                thumbnail,
                null,
            ),
            row.objectOrNull("lengthText")?.let(::text)?.let(::durationMs),
            tentative = true,
        )
    }

    private suspend fun normalize(
        operation: String,
        block: suspend () -> List<Track>,
    ): AppResult<List<Track>> =
        try {
            AppResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppFailureException) {
            AppResult.Failure(e.error)
        } catch (_: SerializationException) {
            failure(operation)
        } catch (_: IllegalArgumentException) {
            failure(operation)
        } catch (_: CatalogSchemaException) {
            failure(operation)
        }

    private fun failure(operation: String) =
        AppResult.Failure(AppError(ErrorCode.PROVIDER_CHANGED, operation, false))
}
