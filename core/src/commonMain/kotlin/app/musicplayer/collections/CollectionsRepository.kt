package app.musicplayer.collections

import app.musicplayer.model.*
import kotlinx.coroutines.flow.Flow

interface CollectionsRepository {
    val overview: Flow<AppResult<LibraryCollections>>

    fun playlist(id: PlaylistId): Flow<AppResult<PlaylistDetail?>>

    suspend fun create(name: String): AppResult<PlaylistId>

    suspend fun rename(id: PlaylistId, name: String): AppResult<Unit>

    suspend fun delete(id: PlaylistId): AppResult<Unit>

    suspend fun append(id: PlaylistId, track: Track): AppResult<PlaylistItemId>

    suspend fun move(id: PlaylistId, itemId: PlaylistItemId, toIndex: Int): AppResult<Unit>

    suspend fun remove(id: PlaylistId, itemId: PlaylistItemId): AppResult<Unit>
}
