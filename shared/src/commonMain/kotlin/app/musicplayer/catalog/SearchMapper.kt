package app.musicplayer.catalog

import app.musicplayer.model.*
import com.metrolist.innertubex.models.MusicResponsiveListItemRenderer
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal data class MappedSearchPage(val page: SearchPage, val continuation: String?)

internal class SearchMapper(
    identities: IdentityStore,
    nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val music = MusicItemMapper(identities, nowMs)

    suspend fun map(body: String, filter: SearchFilter): AppResult<MappedSearchPage> =
        try {
            val root = json.parseToJsonElement(body).requiredObject()
            if (root["error"] != null) schemaFailure()
            val continuation =
                root.objectOrNull("continuationContents")?.objectOrNull("musicShelfContinuation")
            val sections =
                continuation?.let { listOf(JsonObject(mapOf("musicShelfRenderer" to it))) }
                    ?: catalogSections(root)
            var top: SearchItem? = null
            var next: String? = null
            val output = mutableListOf<SearchSection>()
            val seen = mutableSetOf<String>()
            var damaged = false
            suspend fun safeItem(raw: JsonObject, card: Boolean): SearchItem? =
                try {
                    item(raw, card)
                } catch (_: CatalogSchemaException) {
                    damaged = true
                    null
                } catch (_: IllegalArgumentException) {
                    damaged = true
                    null
                }
            var recognized = sections.isEmpty()
            for (section in sections) {
                val card = section.objectOrNull("musicCardShelfRenderer")
                val shelf =
                    card
                        ?: section.objectOrNull("musicShelfRenderer")
                        ?: section.objectOrNull("itemSectionRenderer")
                if (shelf == null) {
                    if (section["messageRenderer"] != null || section["didYouMeanRenderer"] != null)
                        recognized = true
                    continue
                }
                recognized = true
                if (card != null)
                    safeItem(card, true)
                        ?.takeIf { accepts(it, filter) && seen.add(it.key) }
                        ?.let { top = it }
                val items = mutableListOf<SearchItem>()
                for (raw in shelf.arrayOrNull("contents").orEmpty()) {
                    val wrapper = raw.requiredObject()
                    val row =
                        wrapper.objectOrNull("musicResponsiveListItemRenderer")
                            ?: wrapper.objectOrNull("musicTwoRowItemRenderer")
                            ?: continue
                    safeItem(row, false)
                        ?.takeIf { accepts(it, filter) && seen.add(it.key) }
                        ?.let(items::add)
                }
                if (items.isNotEmpty()) {
                    val filter =
                        items.map { it.filter }.distinct().singleOrNull() ?: SearchFilter.ALL
                    val previous = output.lastOrNull()
                    if (previous?.filter == filter) {
                        output[output.lastIndex] = previous.copy(items = previous.items + items)
                    } else output += SearchSection(filter, items)
                }
                if (next == null) next = catalogContinuation(shelf)
            }
            if (!recognized || (damaged && top == null && output.isEmpty())) schemaFailure()
            AppResult.Success(
                MappedSearchPage(
                    SearchPage(
                        top,
                        output,
                        null,
                        if (damaged)
                            AppError(ErrorCode.PROVIDER_CHANGED, "catalog.search.partial", false)
                        else null,
                    ),
                    next,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppFailureException) {
            AppResult.Failure(e.error)
        } catch (_: Exception) {
            AppResult.Failure(AppError(ErrorCode.PROVIDER_CHANGED, "catalog.search.map", false))
        }

    private fun accepts(item: SearchItem, filter: SearchFilter) =
        filter == SearchFilter.ALL || filter == item.filter

    private suspend fun item(raw: JsonObject, card: Boolean): SearchItem? {
        val columns = raw.arrayOrNull("flexColumns")
        val title =
            raw.objectOrNull("title")
                ?: columns
                    ?.firstOrNull()
                    ?.requiredObject()
                    ?.objectOrNull("musicResponsiveListItemFlexColumnRenderer")
                    ?.objectOrNull("text")
                ?: return null
        val titleEndpoint =
            title
                .arrayOrNull("runs")
                ?.firstOrNull()
                ?.requiredObject()
                ?.objectOrNull("navigationEndpoint")
        val endpoint =
            (if (card) raw.objectOrNull("onTap") else raw.objectOrNull("navigationEndpoint"))
                ?: titleEndpoint
                ?: return null
        val name = text(title)?.trim()?.takeIf { it.isNotBlank() } ?: schemaFailure()
        val subtitle =
            raw.objectOrNull("subtitle")
                ?: columns
                    ?.getOrNull(1)
                    ?.requiredObject()
                    ?.objectOrNull("musicResponsiveListItemFlexColumnRenderer")
                    ?.objectOrNull("text")
        val art =
            catalogArtwork(
                raw.objectOrNull("thumbnail")
                    ?: raw.objectOrNull("thumbnailRenderer")
                    ?: JsonObject(emptyMap())
            )
        val browse = endpoint.objectOrNull("browseEndpoint")
        when (endpoint.browsePageType()) {
            "MUSIC_PAGE_TYPE_ARTIST" ->
                return SearchItem.ArtistItem(
                    ArtistRef(
                        name,
                        SourceRef("youtube", "artist", browse!!.requiredString("browseId")),
                    ),
                    art,
                )
            "MUSIC_PAGE_TYPE_ALBUM" -> {
                val artists = credits(subtitle)
                return SearchItem.AlbumItem(
                    AlbumCard(
                        AlbumRef(
                            name,
                            SourceRef("youtube", "album", browse!!.requiredString("browseId")),
                        ),
                        artists,
                        art ?: Artwork(ArtworkKind.PLACEHOLDER, null, null),
                    )
                )
            }
            "MUSIC_PAGE_TYPE_PLAYLIST" -> {
                val id = browse!!.requiredString("browseId").removePrefix("VL")
                if (!isPublicPlaylistId(id)) return null
                val owner =
                    subtitle
                        ?.arrayOrNull("runs")
                        ?.map { it.requiredObject() }
                        ?.firstOrNull {
                            it.objectOrNull("navigationEndpoint")?.browsePageType() !in
                                setOf("MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_ALBUM") &&
                                it.objectOrNull("navigationEndpoint")
                                    ?.objectOrNull("browseEndpoint") != null
                        }
                        ?.stringOrNull("text")
                return SearchItem.PlaylistItem(
                    PublicPlaylistCard(
                        PublicPlaylistRef(name, SourceRef("youtube", "playlist", id)),
                        art,
                        owner,
                        null,
                    )
                )
            }
        }
        val watch = endpoint.objectOrNull("watchEndpoint")
        val id =
            raw.objectOrNull("playlistItemData")?.stringOrNull("videoId")
                ?: watch?.stringOrNull("videoId")
                ?: return null
        val videoType =
            watch
                ?.objectOrNull("watchEndpointMusicSupportedConfigs")
                ?.objectOrNull("watchEndpointMusicConfig")
                ?.stringOrNull("musicVideoType")
        if (videoType?.contains("PODCAST") == true || videoType?.contains("EPISODE") == true)
            return null
        val track =
            if (columns != null)
                music.map(json.decodeFromJsonElement<MusicResponsiveListItemRenderer>(raw), raw)
            else
                music.track(
                    id,
                    name,
                    credits(subtitle),
                    null,
                    art?.copy(
                        kind =
                            if (videoType == "MUSIC_VIDEO_TYPE_ATV") ArtworkKind.RELEASE
                            else ArtworkKind.VIDEO
                    ) ?: Artwork(ArtworkKind.PLACEHOLDER, null, null),
                    subtitle
                        ?.arrayOrNull("runs")
                        ?.mapNotNull { it.requiredObject().stringOrNull("text")?.let(::durationMs) }
                        ?.lastOrNull(),
                    false,
                )
        return track?.let { SearchItem.TrackItem(it, SourceRef("youtube", "video", id)) }
    }

    private fun credits(subtitle: JsonObject?): List<ArtistRef> =
        subtitle?.arrayOrNull("runs").orEmpty().mapNotNull {
            val run = it.requiredObject()
            val endpoint = run.objectOrNull("navigationEndpoint") ?: return@mapNotNull null
            if (endpoint.browsePageType() != "MUSIC_PAGE_TYPE_ARTIST") return@mapNotNull null
            ArtistRef(
                run.requiredString("text"),
                SourceRef(
                    "youtube",
                    "artist",
                    endpoint.requiredObject("browseEndpoint").requiredString("browseId"),
                ),
            )
        }
}
