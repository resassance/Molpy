package app.musicplayer.catalog

import app.musicplayer.model.*
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeHttpException
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.plugins.*
import io.ktor.client.statement.bodyAsText
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

class InnerTubeBrowseCatalog(
    private val session: InnerTube,
    generation: Long,
    identities: IdentityStore,
    nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : BrowseCatalog {
    private val mapper = BrowseMapper(identities, nowMs)
    private val cursors = BrowseCursorStore(generation)
    private val playlists = PlaylistMapper(identities, nowMs)
    private val playlistHeads = PlaylistPageCache<SourceRef, PublicPlaylistCatalog>()
    private val playlistPages = PlaylistPageCache<CatalogCursor, CatalogPage<PlaylistOccurrence>>()

    override suspend fun playlist(
        ref: PublicPlaylistRef,
        refresh: Boolean,
    ): AppResult<PublicPlaylistCatalog> = operation {
        val validated = source(ref.source, "playlist")
        val id = validated.id.removePrefix("VL")
        if (!isPublicPlaylistId(id))
            throw AppFailureException(
                AppError(ErrorCode.SOURCE_UNAVAILABLE, "catalog.playlist.source", false)
            )
        val source = SourceRef("youtube", "playlist", id)
        playlistHeads.get(source, refresh) {
            val request = BrowseRequest(browseId = "VL$id")
            val mapped = playlists.playlist(body(request), ref.copy(source = source)).required()
            PublicPlaylistCatalog(
                mapped.card,
                playlistPage(
                    mapped.tracks,
                    BrowseCursorContext(
                        BrowseKind.PLAYLIST_ROWS,
                        source,
                        request,
                        seenRequests = setOf(request),
                    ),
                ),
            )
        }
    }

    override suspend fun morePlaylist(
        cursor: CatalogCursor
    ): AppResult<CatalogPage<PlaylistOccurrence>> = operation {
        val context = cursors.get(cursor, BrowseKind.PLAYLIST_ROWS)
        playlistPages.get(cursor) {
            val mapped = playlists.more(body(context.request), context.offset).required()
            playlistPage(
                mapped,
                context.copy(seenRequests = context.seenRequests + context.request),
            )
        }
    }

    private fun playlistPage(
        page: MappedCatalogPage<PlaylistOccurrence>,
        context: BrowseCursorContext,
    ): CatalogPage<PlaylistOccurrence> {
        val empty = if (page.items.isEmpty()) context.emptyPages + 1 else 0
        if (page.next != null && empty >= 3) schemaFailure()
        return CatalogPage(
            page.items,
            next(
                page.next,
                context.copy(offset = context.offset + page.items.size, emptyPages = empty),
            ),
        )
    }

    override suspend fun album(ref: AlbumRef): AppResult<AlbumCatalog> = operation {
        val source = source(ref.source, "album")
        val request = BrowseRequest(browseId = source.id)
        val album = mapper.album(body(request), ref).required()
        val context = BrowseTrackContext(album.album, album.artists, album.artwork)
        var page = album.tracks
        var seen = setOf(request)
        if (page.items.isEmpty() && page.next?.browseId != null) {
            val release = requireNotNull(page.next)
            if (release in seen) schemaFailure()
            page = mapper.tracks(body(release), context).required()
            seen = seen + release
        }
        val tracks =
            trackPage(
                page,
                BrowseCursorContext(
                    BrowseKind.TRACKS,
                    source,
                    request,
                    context,
                    seenRequests = seen,
                ),
            )
        AlbumCatalog(album.album, album.artists, album.artwork, tracks)
    }

    override suspend fun artist(ref: ArtistRef): AppResult<ArtistCatalog> = operation {
        val source = source(ref.source, "artist")
        val request = BrowseRequest(browseId = source.id)
        val artist = mapper.artist(body(request), ref).required()
        val context =
            BrowseCursorContext(
                BrowseKind.TRACKS,
                source,
                request,
                BrowseTrackContext(artists = listOf(artist.artist)),
                artist.artist,
                setOf(request),
            )
        ArtistCatalog(
            artist.artist,
            artist.artwork,
            trackPage(artist.tracks, context),
            albumPage(artist.albums, context.copy(kind = BrowseKind.ALBUMS)),
            albumPage(artist.singles, context.copy(kind = BrowseKind.ALBUMS)),
        )
    }

    override suspend fun moreTracks(cursor: CatalogCursor): AppResult<CatalogPage<Track>> =
        operation {
            val context = cursors.get(cursor, BrowseKind.TRACKS)
            val page = mapper.tracks(body(context.request), context.trackContext).required()
            trackPage(page, context.copy(seenRequests = context.seenRequests + context.request))
        }

    override suspend fun moreAlbums(cursor: CatalogCursor): AppResult<CatalogPage<AlbumCard>> =
        operation {
            val context = cursors.get(cursor, BrowseKind.ALBUMS)
            val page = mapper.albums(body(context.request), context.artist).required()
            albumPage(page, context.copy(seenRequests = context.seenRequests + context.request))
        }

    private fun trackPage(
        page: MappedCatalogPage<Track>,
        context: BrowseCursorContext,
    ): CatalogPage<Track> {
        val items = page.items.filterNot { it.id.value in context.seenIds }
        return CatalogPage(
            items,
            next(page.next, context.copy(seenIds = context.seenIds + items.map { it.id.value })),
        )
    }

    private fun albumPage(
        page: MappedCatalogPage<AlbumCard>,
        context: BrowseCursorContext,
    ): CatalogPage<AlbumCard> {
        fun key(card: AlbumCard) =
            requireNotNull(card.album.source).let { "${it.provider}/${it.kind}/${it.id}" }
        val items = page.items.filterNot { key(it) in context.seenIds }
        return CatalogPage(
            items,
            next(page.next, context.copy(seenIds = context.seenIds + items.map(::key))),
        )
    }

    private fun next(request: BrowseRequest?, context: BrowseCursorContext): CatalogCursor? {
        if (request == null) return null
        if (request in context.seenRequests) schemaFailure()
        return cursors.put(context.copy(request = request))
    }

    private suspend fun body(request: BrowseRequest): String {
        val response =
            session.browse(
                YouTubeClient.WEB_REMIX,
                request.browseId,
                request.params,
                request.continuation,
                setLogin = false,
            )
        if (response.status.value !in 200..299)
            throw AppFailureException(httpFailure(response.status.value))
        return response.bodyAsText()
    }

    private fun source(ref: SourceRef?, kind: String): SourceRef =
        ref?.takeIf {
            it.provider in setOf("youtube", "youtube_music") && it.kind == kind
        }
            ?: throw AppFailureException(
                AppError(ErrorCode.SOURCE_UNAVAILABLE, "catalog.browse.source", false)
            )

    private fun <T> AppResult<T>.required(): T =
        when (this) {
            is AppResult.Success -> value
            is AppResult.Failure -> throw AppFailureException(error)
        }

    private suspend fun <T> operation(block: suspend () -> T): AppResult<T> =
        try {
            AppResult.Success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: AppFailureException) {
            AppResult.Failure(error.error)
        } catch (error: InnerTubeHttpException) {
            AppResult.Failure(httpFailure(error.status.value))
        } catch (error: ResponseException) {
            AppResult.Failure(httpFailure(error.response.status.value))
        } catch (_: HttpRequestTimeoutException) {
            failure(ErrorCode.NETWORK, true)
        } catch (_: IOException) {
            failure(ErrorCode.NETWORK, true)
        } catch (_: CatalogSchemaException) {
            failure(ErrorCode.PROVIDER_CHANGED, false)
        } catch (_: Exception) {
            failure(ErrorCode.UNKNOWN, false)
        }

    private fun httpFailure(status: Int) =
        AppError(
            when (status) {
                401 -> ErrorCode.LOGIN_REQUIRED
                403,
                404,
                410 -> ErrorCode.SOURCE_UNAVAILABLE
                407 -> ErrorCode.PROXY_AUTH
                429 -> ErrorCode.RATE_LIMIT
                408,
                425,
                in 500..599 -> ErrorCode.NETWORK
                else -> ErrorCode.PROVIDER_CHANGED
            },
            "catalog.browse.http",
            status == 429 || status == 408 || status == 425 || status in 500..599,
        )

    private fun failure(code: ErrorCode, retryable: Boolean) =
        AppResult.Failure(AppError(code, "catalog.browse", retryable))
}
