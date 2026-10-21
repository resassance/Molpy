package app.musicplayer.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Query(
        """SELECT (SELECT COUNT(*) FROM playlists) + (SELECT COUNT(*) FROM playlist_items)
        + (SELECT COUNT(*) FROM favorites) + (SELECT COUNT(*) FROM offline_assets) + (SELECT COUNT(*) FROM tracks)"""
    )
    fun observeChanges(): Flow<Long>

    @Query("SELECT * FROM playlists ORDER BY createdAtMs DESC, id")
    suspend fun playlists(): List<PlaylistRecord>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun playlist(id: String): PlaylistRecord?

    @Insert suspend fun insert(record: PlaylistRecord)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM playlists WHERE id = :id") suspend fun delete(id: String)

    @Query("SELECT * FROM playlist_items WHERE playlistId = :id ORDER BY position")
    suspend fun items(id: String): List<PlaylistItemRecord>

    @Query("DELETE FROM playlist_items WHERE playlistId = :id") suspend fun deleteItems(id: String)

    @Insert suspend fun insertItems(items: List<PlaylistItemRecord>)

    @Query("SELECT trackId FROM favorites ORDER BY addedAtMs DESC, trackId")
    suspend fun favoriteIds(): List<String>

    @Query(
        """SELECT trackId FROM favorites UNION SELECT trackId FROM playlist_items
        UNION SELECT trackId FROM offline_assets WHERE state = 'AVAILABLE'"""
    )
    suspend fun collectionTrackIds(): List<String>
}
