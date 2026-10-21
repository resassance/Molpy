package app.musicplayer.catalog

import app.musicplayer.model.*
import com.metrolist.innertubex.models.MusicResponsiveListItemRenderer
import kotlinx.serialization.json.*

internal class MusicItemMapper(
    private val identities: IdentityStore,
    private val nowMs: () -> Long,
) {
    suspend fun map(
        row: MusicResponsiveListItemRenderer,
        raw: JsonObject,
        context: BrowseTrackContext = BrowseTrackContext(),
    ): Track? {
        val videoId =
            row.playlistItemData?.videoId
                ?: row.flexColumns
                    .firstOrNull()
                    ?.musicResponsiveListItemFlexColumnRenderer
                    ?.text
                    ?.runs
                    ?.firstOrNull()
                    ?.navigationEndpoint
                    ?.watchEndpoint
                    ?.videoId
                ?: row.navigationEndpoint?.watchEndpoint?.videoId
        if (videoId == null) {
            if (raw.objectOrNull("navigationEndpoint")?.browsePageType() in NON_TRACK_PAGE_TYPES)
                return null
            schemaFailure()
        }
        if (videoId.isBlank()) schemaFailure()
        val title =
            row.flexColumns
                .firstOrNull()
                ?.musicResponsiveListItemFlexColumnRenderer
                ?.text
                ?.runs
                ?.joinToString("") { it.text }
                ?.trim()
                ?.takeIf { it.isNotBlank() } ?: schemaFailure()
        val rawColumns = raw.requiredArray("flexColumns")
        val relationships =
            rawColumns.drop(1).flatMap { column ->
                column
                    .requiredObject()
                    .requiredObject("musicResponsiveListItemFlexColumnRenderer")
                    .objectOrNull("text")
                    ?.arrayOrNull("runs")
                    ?.map { it.requiredObject() }
                    .orEmpty()
            }
        val artists =
            relationships
                .filter { it.objectOrNull("navigationEndpoint")?.browsePageType() == ARTIST_PAGE }
                .map { run ->
                    ArtistRef(
                        run.requiredString("text"),
                        SourceRef(
                            PROVIDER,
                            "artist",
                            run.requiredObject("navigationEndpoint")
                                .requiredObject("browseEndpoint")
                                .requiredString("browseId"),
                        ),
                    )
                }
                .distinct()
        val albumRun = relationships.firstOrNull {
            it.objectOrNull("navigationEndpoint")?.browsePageType() == ALBUM_PAGE
        }
        val album =
            context.album
                ?: albumRun?.let { run ->
                    AlbumRef(
                        run.requiredString("text"),
                        SourceRef(
                            PROVIDER,
                            "album",
                            run.requiredObject("navigationEndpoint")
                                .requiredObject("browseEndpoint")
                                .requiredString("browseId"),
                        ),
                    )
                }
        val fixedDuration =
            raw.arrayOrNull("fixedColumns")
                ?.firstOrNull()
                ?.requiredObject()
                ?.objectOrNull("musicResponsiveListItemFlexColumnRenderer")
                ?.objectOrNull("text")
                ?.let(::text)
                ?.let(::durationMs)
        val duration =
            fixedDuration
                ?: relationships
                    .filter { it["navigationEndpoint"] == null }
                    .mapNotNull { it.stringOrNull("text")?.let(::durationMs) }
                    .lastOrNull()
        val thumbnail =
            row.thumbnail
                ?.musicThumbnailRenderer
                ?.thumbnail
                ?.thumbnails
                ?.maxByOrNull { (it.width ?: 0).toLong() * (it.height ?: 0) }
                ?.url
                ?.takeIf { it.isNotBlank() }
        val titleEndpoint =
            rawColumns
                .firstOrNull()
                ?.requiredObject()
                ?.requiredObject("musicResponsiveListItemFlexColumnRenderer")
                ?.objectOrNull("text")
                ?.arrayOrNull("runs")
                ?.firstOrNull()
                ?.requiredObject()
                ?.objectOrNull("navigationEndpoint")
        val videoType =
            (titleEndpoint ?: raw.objectOrNull("navigationEndpoint"))
                ?.objectOrNull("watchEndpoint")
                ?.objectOrNull("watchEndpointMusicSupportedConfigs")
                ?.objectOrNull("watchEndpointMusicConfig")
                ?.stringOrNull("musicVideoType")
        val artwork =
            Artwork(
                when {
                    thumbnail == null -> ArtworkKind.PLACEHOLDER
                    videoType == "MUSIC_VIDEO_TYPE_ATV" -> ArtworkKind.RELEASE
                    else -> ArtworkKind.VIDEO
                },
                thumbnail,
                null,
            )
        val resolvedArtists =
            if (artists.isNotEmpty()) artists
            else if (context.album != null) {
                val runs =
                    rawColumns
                        .getOrNull(1)
                        ?.requiredObject()
                        ?.objectOrNull("musicResponsiveListItemFlexColumnRenderer")
                        ?.objectOrNull("text")
                        ?.arrayOrNull("runs")
                        .orEmpty()
                val unlinked =
                    runs
                        .takeWhile {
                            it.requiredObject().stringOrNull("text")?.trim() !in setOf("•", "·")
                        }
                        .mapNotNull {
                            it.requiredObject()
                                .stringOrNull("text")
                                ?.trim()
                                ?.takeIf { name -> name.isNotBlank() && durationMs(name) == null }
                                ?.let { name -> ArtistRef(name, null) }
                        }
                unlinked.ifEmpty { context.artists }
            } else context.artists
        val resolvedArtwork =
            context.artwork?.takeIf { context.album != null && it.kind == ArtworkKind.RELEASE }
                ?: artwork.takeUnless { it.kind == ArtworkKind.PLACEHOLDER }
                ?: context.artwork
                ?: artwork
        return track(
            videoId,
            title,
            resolvedArtists,
            album,
            resolvedArtwork,
            duration,
            tentative = false,
        )
    }

    suspend fun track(
        videoId: String,
        title: String,
        artists: List<ArtistRef>,
        album: AlbumRef?,
        artwork: Artwork,
        duration: Long?,
        tentative: Boolean,
    ): Track {
        val source = SourceRef(PROVIDER, "video", videoId)
        return Track(
            identities.getOrCreate(source),
            title,
            artists,
            album,
            artwork,
            duration,
            setOf(source),
            MetadataProvenance(
                if (tentative) source else SourceRef("youtube_music", "song", videoId),
                nowMs(),
                tentative,
            ),
            listOf(
                PlaybackCandidate(
                    CandidateId("youtube:video:$videoId"),
                    source,
                    "",
                    duration,
                    if (tentative) MatchConfidence.TENTATIVE else MatchConfidence.HIGH,
                )
            ),
        )
    }

    private companion object {
        const val PROVIDER = "youtube"
        const val ARTIST_PAGE = "MUSIC_PAGE_TYPE_ARTIST"
        const val ALBUM_PAGE = "MUSIC_PAGE_TYPE_ALBUM"
        val NON_TRACK_PAGE_TYPES = setOf(ARTIST_PAGE, ALBUM_PAGE, "MUSIC_PAGE_TYPE_PLAYLIST")
    }
}
