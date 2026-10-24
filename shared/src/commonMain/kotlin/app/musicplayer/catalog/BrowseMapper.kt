package app.musicplayer.catalog

import app.musicplayer.model.*
import com.metrolist.innertubex.models.MusicResponsiveListItemRenderer
import com.metrolist.innertubex.models.response.BrowseResponse
import io.ktor.http.Url
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

internal class BrowseMapper(
    identities: IdentityStore,
    nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val items = MusicItemMapper(identities, nowMs)

    suspend fun album(body: String, ref: AlbumRef): AppResult<MappedAlbum> = mapped {
        val root = root(body)
        val sections = sections(root)
        val header = header(root, sections)
        val album = ref.copy(name = header?.objectOrNull("title")?.let(::text) ?: ref.name)
        val artists =
            header
                ?.let {
                    artists(it.objectOrNull("straplineTextOne") ?: it.objectOrNull("subtitle"))
                }
                .orEmpty()
        val artwork = header?.let(::artwork) ?: Artwork(ArtworkKind.PLACEHOLDER, null, null)
        val context = BrowseTrackContext(album, artists, artwork)
        var tracks = trackSections(sections, context)
        if (tracks.items.isEmpty() && tracks.next == null) {
            val canonical =
                root
                    .objectOrNull("microformat")
                    ?.objectOrNull("microformatDataRenderer")
                    ?.stringOrNull("urlCanonical")
            if (canonical != null) {
                val url = Url(canonical)
                val playlist =
                    url.parameters["list"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
                        ?: schemaFailure()
                if (url.host != "music.youtube.com" || url.encodedPath != "/playlist")
                    schemaFailure()
                // Source-provided canonical release playlist, as used by the maintained album
                // client.
                tracks = tracks.copy(next = BrowseRequest(browseId = "VL$playlist"))
            }
        }
        MappedAlbum(album, artists, artwork, tracks)
    }

    suspend fun artist(body: String, ref: ArtistRef): AppResult<MappedArtist> = mapped {
        val root = root(body)
        val sections = sections(root)
        val header = ArtistArtworkMapper.musicArtistHeader(root)
        val artist = ref.copy(name = header?.objectOrNull("title")?.let(::text) ?: ref.name)
        val albums = mutableListOf<AlbumCard>()
        val singles = mutableListOf<AlbumCard>()
        var albumNext: BrowseRequest? = null
        var singleNext: BrowseRequest? = null
        for (section in sections) {
            val shelf = section.objectOrNull("musicCarouselShelfRenderer") ?: continue
            val shelfHeader =
                shelf.objectOrNull("header")?.objectOrNull("musicCarouselShelfBasicHeaderRenderer")
            val title = shelfHeader?.objectOrNull("title")?.let(::text).orEmpty()
            val cards =
                shelf.arrayOrNull("contents").orEmpty().mapNotNull {
                    albumCard(it.requiredObject(), artist)
                }
            if (cards.isEmpty()) continue
            val isSingle =
                title.contains("single", true) ||
                    title.contains("сингл", true) ||
                    Regex("\\bEPs?\\b", RegexOption.IGNORE_CASE).containsMatchIn(title)
            val next =
                shelfHeader
                    ?.objectOrNull("moreContentButton")
                    ?.objectOrNull("buttonRenderer")
                    ?.objectOrNull("navigationEndpoint")
                    ?.let(::endpoint) ?: next(shelf)
            if (isSingle) {
                singles += cards
                singleNext = next
            } else {
                albums += cards
                albumNext = next
            }
        }
        MappedArtist(
            artist,
            ArtistArtworkMapper.photo(header),
            trackSections(sections, BrowseTrackContext(artists = listOf(artist))),
            MappedCatalogPage(albums.distinctBy { it.album.source }, albumNext),
            MappedCatalogPage(singles.distinctBy { it.album.source }, singleNext),
        )
    }

    suspend fun tracks(
        body: String,
        context: BrowseTrackContext,
    ): AppResult<MappedCatalogPage<Track>> = mapped {
        val root = root(body)
        val continuation = continuation(root)
        if (continuation != null) trackShelf(continuation, context)
        else trackSections(sections(root), context)
    }

    suspend fun albums(body: String, artist: ArtistRef?): AppResult<MappedCatalogPage<AlbumCard>> =
        mapped {
            val root = root(body)
            val shelves =
                continuation(root)?.let(::listOf)
                    ?: sections(root).mapNotNull { section ->
                        section.objectOrNull("gridRenderer")
                            ?: section.objectOrNull("musicCarouselShelfRenderer")
                    }
            if (shelves.isEmpty()) schemaFailure()
            val cards = shelves.flatMap { shelf ->
                (shelf.arrayOrNull("items") ?: shelf.arrayOrNull("contents")).orEmpty().mapNotNull {
                    albumCard(it.requiredObject(), artist)
                }
            }
            MappedCatalogPage(cards, shelves.firstNotNullOfOrNull(::next))
        }

    private fun root(body: String): JsonObject {
        json.decodeFromString<BrowseResponse>(body)
        val root = json.parseToJsonElement(body).requiredObject()
        if (
            root["error"] != null ||
                root.arrayOrNull("alerts").orEmpty().any {
                    it.requiredObject().objectOrNull("alertRenderer")?.stringOrNull("type") ==
                        "ERROR"
                }
        )
            throw AppFailureException(
                AppError(ErrorCode.SOURCE_UNAVAILABLE, "catalog.browse", false)
            )
        return root
    }

    private fun sections(root: JsonObject): List<JsonObject> {
        val contents = root.objectOrNull("contents") ?: schemaFailure()
        val lists = mutableListOf<JsonObject>()
        contents.objectOrNull("sectionListRenderer")?.let(lists::add)
        for (kind in
            listOf("singleColumnBrowseResultsRenderer", "twoColumnBrowseResultsRenderer")) {
            val container = contents.objectOrNull(kind) ?: continue
            container.arrayOrNull("tabs").orEmpty().forEach { tab ->
                tab.requiredObject()
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
        return lists
            .flatMap { it.requiredArray("contents").map { value -> value.requiredObject() } }
            .onEach { section ->
                if (section.keys.none { it in KNOWN_SECTIONS }) schemaFailure()
            }
    }

    private fun header(root: JsonObject, sections: List<JsonObject>): JsonObject? {
        val headers = listOfNotNull(root.objectOrNull("header")) + sections
        return headers.firstNotNullOfOrNull { value ->
            HEADER_TYPES.firstNotNullOfOrNull { value.objectOrNull(it) }
        }
    }

    private suspend fun trackSections(
        sections: List<JsonObject>,
        context: BrowseTrackContext,
    ): MappedCatalogPage<Track> {
        val shelves = sections.mapNotNull {
            it.objectOrNull("musicPlaylistShelfRenderer") ?: it.objectOrNull("musicShelfRenderer")
        }
        val pages = shelves.map { trackShelf(it, context) }
        return MappedCatalogPage(pages.flatMap { it.items }, pages.firstNotNullOfOrNull { it.next })
    }

    private suspend fun trackShelf(
        shelf: JsonObject,
        context: BrowseTrackContext,
    ): MappedCatalogPage<Track> {
        val rows =
            (shelf.arrayOrNull("contents") ?: shelf.arrayOrNull("continuationItems"))
                ?: schemaFailure()
        val tracks = rows.mapNotNull { value ->
            val raw = value.requiredObject()
            val renderer = raw.objectOrNull("musicResponsiveListItemRenderer")
            when {
                renderer != null ->
                    items.map(
                        json.decodeFromJsonElement<MusicResponsiveListItemRenderer>(renderer),
                        renderer,
                        context,
                    )
                raw["continuationItemRenderer"] != null -> null
                else -> schemaFailure()
            }
        }
        val more =
            next(shelf)
                ?: shelf
                    .objectOrNull("title")
                    ?.arrayOrNull("runs")
                    ?.firstOrNull()
                    ?.requiredObject()
                    ?.objectOrNull("navigationEndpoint")
                    ?.let(::endpoint)
        return MappedCatalogPage(tracks, more)
    }

    private fun continuation(root: JsonObject): JsonObject? {
        val contents = root.objectOrNull("continuationContents")
        if (contents != null)
            return listOf(
                    "musicShelfContinuation",
                    "musicPlaylistShelfContinuation",
                    "gridContinuation",
                    "sectionListContinuation",
                )
                .firstNotNullOfOrNull { contents.objectOrNull(it) } ?: schemaFailure()
        val actions =
            root.arrayOrNull("onResponseReceivedActions")
                ?: root.arrayOrNull("onResponseReceivedCommands")
        return actions?.firstNotNullOfOrNull {
            it.requiredObject().objectOrNull("appendContinuationItemsAction")
        }
    }

    private fun albumCard(raw: JsonObject, artist: ArtistRef?): AlbumCard? {
        val card = raw.objectOrNull("musicTwoRowItemRenderer") ?: return null
        val navigation = card.objectOrNull("navigationEndpoint") ?: return null
        if (navigation.browsePageType() != "MUSIC_PAGE_TYPE_ALBUM") return null
        val id = navigation.requiredObject("browseEndpoint").requiredString("browseId")
        val title =
            card.objectOrNull("title")?.let(::text)?.takeIf { it.isNotBlank() } ?: schemaFailure()
        return AlbumCard(
            AlbumRef(title, SourceRef("youtube", "album", id)),
            artists(card.objectOrNull("subtitle")).ifEmpty { listOfNotNull(artist) },
            artwork(card) ?: Artwork(ArtworkKind.PLACEHOLDER, null, null),
        )
    }

    private fun artists(text: JsonObject?): List<ArtistRef> =
        text
            ?.arrayOrNull("runs")
            .orEmpty()
            .mapNotNull { value ->
                val run = value.requiredObject()
                val navigation = run.objectOrNull("navigationEndpoint") ?: return@mapNotNull null
                if (navigation.browsePageType() != "MUSIC_PAGE_TYPE_ARTIST") return@mapNotNull null
                ArtistRef(
                    run.requiredString("text"),
                    SourceRef(
                        "youtube",
                        "artist",
                        navigation.requiredObject("browseEndpoint").requiredString("browseId"),
                    ),
                )
            }
            .distinct()

    private fun artwork(container: JsonObject): Artwork? {
        fun thumbnails(value: JsonElement): List<JsonObject> =
            when (value) {
                is JsonObject ->
                    value.arrayOrNull("thumbnails")?.map { it.requiredObject() }
                        ?: value.values.flatMap(::thumbnails)
                is JsonArray -> value.flatMap(::thumbnails)
                else -> emptyList()
            }
        val image =
            listOfNotNull(
                    container.objectOrNull("thumbnail"),
                    container.objectOrNull("thumbnailRenderer"),
                    container.objectOrNull("foregroundThumbnail"),
                )
                .flatMap(::thumbnails)
                .maxByOrNull {
                    (it["width"]?.jsonPrimitive?.longOrNull ?: 0) *
                        (it["height"]?.jsonPrimitive?.longOrNull ?: 0)
                }
        return image?.stringOrNull("url")?.let { Artwork(ArtworkKind.RELEASE, it, null) }
    }

    private fun endpoint(navigation: JsonObject): BrowseRequest? =
        navigation.objectOrNull("browseEndpoint")?.let {
            it.stringOrNull("browseId")?.let { id -> BrowseRequest(id, it.stringOrNull("params")) }
        }

    private fun next(container: JsonObject): BrowseRequest? {
        val token =
            container.arrayOrNull("continuations").orEmpty().firstNotNullOfOrNull { entry ->
                val raw = entry.requiredObject()
                (raw.objectOrNull("nextContinuationData")
                        ?: raw.objectOrNull("reloadContinuationData"))
                    ?.stringOrNull("continuation")
            }
                ?: (container.arrayOrNull("contents")
                        ?: container.arrayOrNull("continuationItems")
                        ?: container.arrayOrNull("items"))
                    .orEmpty()
                    .firstNotNullOfOrNull {
                        it.requiredObject()
                            .objectOrNull("continuationItemRenderer")
                            ?.objectOrNull("continuationEndpoint")
                            ?.objectOrNull("continuationCommand")
                            ?.stringOrNull("token")
                    }
        return token?.let { BrowseRequest(continuation = it) }
    }

    private suspend fun <T> mapped(block: suspend () -> T): AppResult<T> =
        try {
            AppResult.Success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: AppFailureException) {
            AppResult.Failure(error.error)
        } catch (_: SerializationException) {
            changed()
        } catch (_: IllegalArgumentException) {
            changed()
        } catch (_: CatalogSchemaException) {
            changed()
        }

    private fun changed() =
        AppResult.Failure(AppError(ErrorCode.PROVIDER_CHANGED, "catalog.browse.map", false))

    private companion object {
        val HEADER_TYPES =
            setOf(
                "musicResponsiveHeaderRenderer",
                "musicDetailHeaderRenderer",
                "musicImmersiveHeaderRenderer",
                "musicVisualHeaderRenderer",
                "musicHeaderRenderer",
            )
        val KNOWN_SECTIONS =
            HEADER_TYPES +
                setOf(
                    "musicShelfRenderer",
                    "musicPlaylistShelfRenderer",
                    "musicCarouselShelfRenderer",
                    "gridRenderer",
                    "musicDescriptionShelfRenderer",
                    "musicItemSectionRenderer",
                    "musicCardShelfRenderer",
                    "musicDividerShelfRenderer",
                )
    }
}
