package app.musicplayer.storage

import androidx.room.*

@Dao
interface OfflineDao {
    @Query("SELECT * FROM offline_assets WHERE id = :id") suspend fun asset(id: String): OfflineRecord?
    @Query("SELECT * FROM offline_assets WHERE trackId = :trackId AND state = 'AVAILABLE' ORDER BY id LIMIT 1")
    suspend fun available(trackId: String): OfflineRecord?
    @Query("SELECT * FROM offline_assets ORDER BY id") suspend fun all(): List<OfflineRecord>
    @Query("SELECT * FROM offline_assets WHERE trackId = :trackId AND candidateId = :candidateId AND representationId = :representationId")
    suspend fun representation(trackId: String, candidateId: String, representationId: String): OfflineRecord?
    @Query("SELECT COUNT(*) FROM offline_assets WHERE trackId = :trackId AND candidateId = :candidateId")
    suspend fun references(trackId: String, candidateId: String): Int
    @Upsert suspend fun put(record: OfflineRecord)
    @Query("DELETE FROM offline_assets WHERE id = :id") suspend fun remove(id: String)
}
