package app.musicplayer.queue

import app.musicplayer.catalog.*
import app.musicplayer.fixtures.Fixtures
import app.musicplayer.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive

internal class QueuePlaylistTestFixture(
    scope: CoroutineScope,
    random: kotlin.random.Random = kotlin.random.Random.Default,
) {
    val playback = PlaylistQueueFixture(scope, random).also { it.tracks.clear() }
    val ref = PublicPlaylistRef("Mix", SourceRef("youtube", "playlist", "PLtest"))
    var beforeRemember: suspend (List<Track>) -> Unit = {}
    var beforeRememberOne: suspend (Track) -> Unit = {}
    var beforeCommit: suspend (QueueSnapshot) -> Unit = {}
    var commits = 0
    var failCommit = false
    var durable = QueueSnapshot()
    val catalog = Catalog()
    val commands =
        QueuePlaybackCommands(
            playback.queue,
            { track ->
                beforeRememberOne(track)
                remember(listOf(track))
            },
            ::remember,
        )
    val loader: QueuePlaylistLoader =
        QueuePlaylistLoader(
            catalog,
            playback.queue,
            commands,
            ::remember,
            { snapshot, _ ->
                beforeCommit(snapshot)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                commits++
                if (failCommit)
                    AppResult.Failure(AppError(ErrorCode.DATABASE, "library.save", false))
                else {
                    durable = snapshot
                    AppResult.Success(Unit)
                }
            },
            scope,
        )

    fun track(id: String) = Fixtures.track(id, sourceId = id)

    fun page(offset: Int, ids: List<String>, next: String? = null) =
        CatalogPage(
            ids.mapIndexed { index, id ->
                PlaylistOccurrence(
                    PlaylistRowIdentity(
                        offset + index,
                        "row-${offset + index}",
                        SourceRef("youtube", "video", id),
                    ),
                    track(id),
                    false,
                )
            },
            next?.let(::CatalogCursor),
        )

    fun request(ids: List<String> = listOf("a"), next: String? = "tail", selected: Int = 0) =
        PlaylistPlaybackRequest(ref, page(0, ids, next).items, selected, next?.let(::CatalogCursor))

    private suspend fun remember(tracks: List<Track>): AppResult<Unit> {
        beforeRemember(tracks)
        tracks.forEach { playback.tracks[it.id] = it }
        return AppResult.Success(Unit)
    }

    fun future() = QueueOrder.upcoming(playback.queue.state.value).map { it.trackId.value }

    class Catalog : BrowseCatalog {
        var headCalls = 0
        var head: suspend (PublicPlaylistRef, Boolean) -> AppResult<PublicPlaylistCatalog> =
            { _, _ ->
                error("Unexpected head")
            }

        override suspend fun playlist(
            ref: PublicPlaylistRef,
            refresh: Boolean,
        ): AppResult<PublicPlaylistCatalog> {
            headCalls++
            return head(ref, refresh)
        }

        var calls = 0
        var read: suspend (CatalogCursor) -> AppResult<CatalogPage<PlaylistOccurrence>> = {
            error("Unexpected page")
        }

        override suspend fun morePlaylist(
            cursor: CatalogCursor
        ): AppResult<CatalogPage<PlaylistOccurrence>> {
            calls++
            return read(cursor)
        }

        override suspend fun album(ref: AlbumRef): AppResult<AlbumCatalog> =
            error("Unexpected album browse")

        override suspend fun artist(ref: ArtistRef): AppResult<ArtistCatalog> =
            error("Unexpected artist browse")

        override suspend fun moreTracks(cursor: CatalogCursor): AppResult<CatalogPage<Track>> =
            error("Unexpected track browse")

        override suspend fun moreAlbums(cursor: CatalogCursor): AppResult<CatalogPage<AlbumCard>> =
            error("Unexpected album page")
    }
}
