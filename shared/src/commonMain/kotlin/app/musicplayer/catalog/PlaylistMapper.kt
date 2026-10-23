package app.musicplayer.catalog

import app.musicplayer.model.*
import com.metrolist.innertubex.models.MusicResponsiveListItemRenderer
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal data class MappedPlaylist(
    val card: PublicPlaylistCard,
    val tracks: MappedCatalogPage<PlaylistOccurrence>,
)

internal class PlaylistMapper(
    identities: IdentityStore,
    nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val music = MusicItemMapper(identities, nowMs)

    suspend fun playlist(body: String, ref: PublicPlaylistRef): AppResult<MappedPlaylist> = mapped {
        val root = root(body)
        val sections = sections(root)
        val headers = listOfNotNull(root.objectOrNull("header")) + sections
        val header = headers.firstNotNullOfOrNull { h ->
            h.objectOrNull("musicResponsiveHeaderRenderer")
                ?: h.objectOrNull("musicDetailHeaderRenderer")
                ?: h.objectOrNull("musicEditablePlaylistDetailHeaderRenderer")
                    ?.objectOrNull("header")
                    ?.let {
                        it.objectOrNull("musicResponsiveHeaderRenderer")
                            ?: it.objectOrNull("musicDetailHeaderRenderer")
                    }
        }
        val title =
            header?.objectOrNull("title")?.let(::text)?.takeIf { it.isNotBlank() } ?: ref.title
        val credits = header?.objectOrNull("straplineTextOne") ?: header?.objectOrNull("subtitle")
        val owner =
            credits
                ?.arrayOrNull("runs")
                ?.map { it.requiredObject() }
                ?.firstOrNull {
                    it.objectOrNull("navigationEndpoint")?.objectOrNull("browseEndpoint") != null
                }
                ?.stringOrNull("text")
        MappedPlaylist(
            PublicPlaylistCard(ref.copy(title = title), header?.let(::catalogArtwork), owner, null),
            page(sections, 0),
        )
    }

    suspend fun more(body: String, offset: Int): AppResult<MappedCatalogPage<PlaylistOccurrence>> =
        mapped {
            val root = root(body)
            val continuation = root.objectOrNull("continuationContents")
            val direct =
                continuation?.objectOrNull("musicPlaylistShelfContinuation")
                    ?: continuation?.objectOrNull("musicShelfContinuation")
            val section = continuation?.objectOrNull("sectionListContinuation")
            val actions =
                listOf(
                        "onResponseReceivedActions",
                        "onResponseReceivedCommands",
                        "onResponseReceivedEndpoints",
                    )
                    .flatMap { root.arrayOrNull(it).orEmpty() }
                    .mapNotNull {
                        it.requiredObject().objectOrNull("appendContinuationItemsAction")
                    }
            when {
                direct != null -> shelf(direct, offset)
                section != null ->
                    page(section.requiredArray("contents").map { it.requiredObject() }, offset)
                actions.isNotEmpty() ->
                    shelf(
                        JsonObject(
                            mapOf(
                                "contents" to
                                    JsonArray(
                                        actions.flatMap { it.requiredArray("continuationItems") }
                                    )
                            )
                        ),
                        offset,
                    )
                else -> schemaFailure()
            }
        }

    private fun root(body: String): JsonObject {
        val root = json.parseToJsonElement(body).requiredObject()
        if (
            root["error"] != null ||
                root.arrayOrNull("alerts").orEmpty().any {
                    it.requiredObject().objectOrNull("alertRenderer")?.stringOrNull("type") ==
                        "ERROR"
                }
        )
            throw AppFailureException(
                AppError(ErrorCode.SOURCE_UNAVAILABLE, "catalog.playlist", false)
            )
        return root
    }

    private fun sections(root: JsonObject): List<JsonObject> {
        val contents = root.objectOrNull("contents") ?: schemaFailure()
        val lists = mutableListOf<JsonObject>()
        contents.objectOrNull("sectionListRenderer")?.let(lists::add)
        for (type in
            listOf("singleColumnBrowseResultsRenderer", "twoColumnBrowseResultsRenderer")) {
            val container = contents.objectOrNull(type) ?: continue
            container.arrayOrNull("tabs").orEmpty().forEach {
                it.requiredObject()
                    .objectOrNull("tabRenderer")
                    ?.objectOrNull("content")
                    ?.objectOrNull("sectionListRenderer")
                    ?.let(lists::add)
            }
            container
                .objectOrNull("secondaryContents")
                ?.objectOrNull("sectionListRenderer")
                ?.let(lists::add)
        }
        if (lists.isEmpty()) schemaFailure()
        return lists.flatMap {
            it.requiredArray("contents").map { child -> child.requiredObject() }
        }
    }

    private suspend fun page(
        sections: List<JsonObject>,
        offset: Int,
    ): MappedCatalogPage<PlaylistOccurrence> {
        val shelves = sections.mapNotNull {
            it.objectOrNull("musicPlaylistShelfRenderer") ?: it.objectOrNull("musicShelfRenderer")
        }
        if (shelves.isEmpty()) {
            if (sections.any { it["messageRenderer"] != null })
                return MappedCatalogPage(emptyList(), null)
            schemaFailure()
        }
        val rows = mutableListOf<PlaylistOccurrence>()
        var next: BrowseRequest? = null
        for (value in shelves) {
            val mapped = shelf(value, offset + rows.size)
            rows += mapped.items
            if (next == null) next = mapped.next
        }
        return MappedCatalogPage(rows, next)
    }

    private suspend fun shelf(
        value: JsonObject,
        offset: Int,
    ): MappedCatalogPage<PlaylistOccurrence> {
        val contents = value.arrayOrNull("contents") ?: schemaFailure()
        val rows = mutableListOf<PlaylistOccurrence>()
        for (wrapper in contents) {
            val raw = wrapper.requiredObject()
            if (raw["continuationItemRenderer"] != null) continue
            val row = raw.objectOrNull("musicResponsiveListItemRenderer") ?: schemaFailure()
            val data = row.objectOrNull("playlistItemData")
            val first =
                row.arrayOrNull("flexColumns")
                    ?.firstOrNull()
                    ?.requiredObject()
                    ?.objectOrNull("musicResponsiveListItemFlexColumnRenderer")
                    ?.objectOrNull("text")
            val endpoint =
                row.objectOrNull("navigationEndpoint")
                    ?: first
                        ?.arrayOrNull("runs")
                        ?.firstOrNull()
                        ?.requiredObject()
                        ?.objectOrNull("navigationEndpoint")
            val id =
                (data?.stringOrNull("videoId")
                        ?: endpoint?.objectOrNull("watchEndpoint")?.stringOrNull("videoId"))
                    ?.takeIf { it.isNotBlank() }
            val unavailable =
                id == null ||
                    row.stringOrNull("musicItemRendererDisplayPolicy") ==
                        "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT"
            if (first?.let(::text).isNullOrBlank()) schemaFailure()
            val identity =
                PlaylistRowIdentity(
                    offset + rows.size,
                    data?.stringOrNull("playlistSetVideoId")
                        ?: row.stringOrNull("playlistSetVideoId"),
                    id?.let { SourceRef("youtube", "video", it) },
                )
            val track =
                if (unavailable) null
                else
                    music.map(json.decodeFromJsonElement<MusicResponsiveListItemRenderer>(row), row)
                        ?: schemaFailure()
            rows += PlaylistOccurrence(identity, track, unavailable)
        }
        return MappedCatalogPage(
            rows,
            catalogContinuation(value)?.let { BrowseRequest(continuation = it) },
        )
    }

    private suspend fun <T> mapped(block: suspend () -> T): AppResult<T> =
        try {
            AppResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppFailureException) {
            AppResult.Failure(e.error)
        } catch (_: Exception) {
            AppResult.Failure(AppError(ErrorCode.PROVIDER_CHANGED, "catalog.playlist.map", false))
        }
}
