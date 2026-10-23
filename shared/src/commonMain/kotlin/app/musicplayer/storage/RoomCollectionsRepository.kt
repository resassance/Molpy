package app.musicplayer.storage

import androidx.room.*
import app.musicplayer.collections.*
import app.musicplayer.model.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*

class RoomCollectionsRepository(
    private val database: MusicDatabase,
    private val nowMs: () -> Long,
) : CollectionsRepository {
    private val dao = database.collectionDao()
    private val tracks = TrackRecords(database)

    override val overview: Flow<AppResult<LibraryCollections>> = observe {
        val favorites = dao.favoriteIds().mapNotNull { tracks.load(TrackId(it)) }
        val collected = dao.collectionTrackIds().mapNotNull { tracks.load(TrackId(it)) }
        val (albums, artists) = CollectionGrouping.build(collected)
        val playlists =
            dao.playlists().map { playlist ->
                val items = loadItems(playlist.id)
                PlaylistSummary(
                    PlaylistId(playlist.id),
                    playlist.name,
                    items.size,
                    items.map { it.track.artwork }.distinct().take(4),
                )
            }
        LibraryCollections(favorites, playlists, albums, artists)
    }

    override fun playlist(id: PlaylistId): Flow<AppResult<PlaylistDetail?>> = observe {
        dao.playlist(id.value)?.let { PlaylistDetail(id, it.name, loadItems(id.value)) }
    }

    override suspend fun create(name: String): AppResult<PlaylistId> = write {
        val id = PlaylistId(Uuid.random().toString())
        dao.insert(PlaylistRecord(id.value, validName(name), nowMs()))
        id
    }

    override suspend fun rename(id: PlaylistId, name: String): AppResult<Unit> = write {
        dao.rename(id.value, validName(name))
    }

    override suspend fun delete(id: PlaylistId): AppResult<Unit> = write { dao.delete(id.value) }

    override suspend fun append(id: PlaylistId, track: Track): AppResult<PlaylistItemId> = write {
        require(dao.playlist(id.value) != null)
        val trackId = tracks.upsert(track, nowMs())
        val itemId = PlaylistItemId(Uuid.random().toString())
        val items = dao.items(id.value)
        dao.insertItems(
            listOf(PlaylistItemRecord(itemId.value, id.value, trackId.value, items.size))
        )
        itemId
    }

    override suspend fun move(
        id: PlaylistId,
        itemId: PlaylistItemId,
        toIndex: Int,
    ): AppResult<Unit> = write {
        val items = dao.items(id.value).toMutableList()
        require(toIndex in items.indices)
        val from = items.indexOfFirst { it.id == itemId.value }
        require(from >= 0)
        items.add(toIndex, items.removeAt(from))
        replaceItems(id, items)
    }

    override suspend fun remove(id: PlaylistId, itemId: PlaylistItemId): AppResult<Unit> = write {
        val items = dao.items(id.value)
        if (items.any { it.id == itemId.value })
            replaceItems(id, items.filterNot { it.id == itemId.value })
    }

    private suspend fun replaceItems(id: PlaylistId, items: List<PlaylistItemRecord>) {
        dao.deleteItems(id.value)
        dao.insertItems(items.mapIndexed { position, item -> item.copy(position = position) })
    }

    private suspend fun loadItems(id: String): List<PlaylistItem> =
        dao.items(id).map { item ->
            PlaylistItem(
                PlaylistItemId(item.id),
                item.position,
                requireNotNull(tracks.load(TrackId(item.trackId))),
            )
        }

    private fun validName(name: String): String =
        name.trim().also { require(it.isNotEmpty() && it.length <= 120) }

    private fun <T> observe(block: suspend () -> T): Flow<AppResult<T>> =
        dao.observeChanges()
            .map {
                database.useReaderConnection { connection ->
                    connection.deferredTransaction { AppResult.Success(block()) as AppResult<T> }
                }
            }
            .catch { error ->
                if (error is CancellationException) currentCoroutineContext().ensureActive()
                emit(AppResult.Failure(AppError(ErrorCode.DATABASE, "collections.observe", true)))
            }

    private suspend fun <T> write(block: suspend () -> T): AppResult<T> =
        try {
            database.useWriterConnection { connection ->
                connection.immediateTransaction { AppResult.Success(block()) }
            }
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            AppResult.Failure(AppError(ErrorCode.DATABASE, "collections.write", true))
        } catch (_: IllegalArgumentException) {
            AppResult.Failure(AppError(ErrorCode.INVALID_CONFIG, "collections.write", false))
        } catch (_: Exception) {
            AppResult.Failure(AppError(ErrorCode.DATABASE, "collections.write", true))
        }
}
