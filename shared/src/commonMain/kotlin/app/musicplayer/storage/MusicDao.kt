package app.musicplayer.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao {
    @Query("SELECT COUNT(*) FROM settings") suspend fun settingsCount(): Int

    @Query(
        "SELECT * FROM external_identity WHERE provider = :provider AND kind = :kind AND sourceId = :sourceId"
    )
    suspend fun identity(provider: String, kind: String, sourceId: String): ExternalIdentityRecord?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIdentity(record: ExternalIdentityRecord)

    @Query("SELECT * FROM external_identity WHERE trackId = :id ORDER BY provider, kind, sourceId")
    suspend fun identities(id: String): List<ExternalIdentityRecord>

    @Upsert suspend fun putTrack(record: TrackRecord)

    @Query("SELECT * FROM tracks WHERE id = :id") suspend fun track(id: String): TrackRecord?

    @Query("SELECT DISTINCT artworkLocalKey FROM tracks WHERE artworkLocalKey IS NOT NULL")
    suspend fun retainedArtworkKeys(): List<String>

    @Query("DELETE FROM tracks WHERE id = :id") suspend fun deleteTrack(id: String)

    @Query("SELECT * FROM candidates WHERE trackId = :id ORDER BY position")
    suspend fun candidates(id: String): List<CandidateRecord>

    @Query("DELETE FROM candidates WHERE trackId = :id") suspend fun deleteCandidates(id: String)

    @Insert suspend fun insertCandidates(records: List<CandidateRecord>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFavorite(record: FavoriteRecord)

    @Query("DELETE FROM favorites WHERE trackId = :id") suspend fun deleteFavorite(id: String)

    @Query(
        "SELECT t.* FROM tracks t INNER JOIN favorites f ON f.trackId = t.id ORDER BY f.addedAtMs DESC, t.id"
    )
    fun observeFavorites(): Flow<List<TrackRecord>>

    @Query(
        "SELECT t.* FROM tracks t INNER JOIN recent_history r ON r.trackId = t.id ORDER BY r.lastPlayedAtMs DESC, t.id"
    )
    fun observeRecent(): Flow<List<TrackRecord>>

    @Upsert suspend fun putRecent(record: RecentRecord)

    @Query(
        "DELETE FROM recent_history WHERE trackId NOT IN (SELECT trackId FROM recent_history ORDER BY lastPlayedAtMs DESC, trackId LIMIT :limit)"
    )
    suspend fun trimRecent(limit: Int)

    @Query("DELETE FROM queue_entries") suspend fun deleteQueueEntries()

    @Insert suspend fun insertQueueEntries(records: List<QueueEntryRecord>)

    @Upsert suspend fun putQueueSnapshot(record: QueueSnapshotRecord)

    @Query("SELECT * FROM queue_entries ORDER BY position")
    suspend fun queueEntries(): List<QueueEntryRecord>

    @Query("SELECT * FROM queue_snapshot WHERE singletonId = 1")
    suspend fun queueSnapshot(): QueueSnapshotRecord?

    @Query("SELECT * FROM lyrics_cache WHERE trackId = :id AND candidateId = :candidateId")
    suspend fun lyrics(id: String, candidateId: String): LyricsRecord?

    @Upsert suspend fun putLyrics(record: LyricsRecord)

    @Query(
        "DELETE FROM lyrics_cache WHERE kind = 'UNAVAILABLE' AND (cachedAtMs > :nowMs OR cachedAtMs <= :cutoffMs) AND NOT EXISTS (SELECT 1 FROM offline_assets o WHERE o.trackId = lyrics_cache.trackId AND o.candidateId = lyrics_cache.candidateId)"
    )
    suspend fun pruneUnavailableLyrics(nowMs: Long, cutoffMs: Long)

    @Query("SELECT * FROM catalog_cache WHERE query = :query AND musicOnly = :musicOnly")
    suspend fun cachedSearch(query: String, musicOnly: Boolean): CatalogCacheRecord?

    @Upsert suspend fun putCachedSearch(record: CatalogCacheRecord)

    @Query("DELETE FROM catalog_cache WHERE cachedAtMs > :nowMs OR cachedAtMs <= :cutoffMs")
    suspend fun pruneSearch(nowMs: Long, cutoffMs: Long)

    @Query(
        """SELECT t.* FROM tracks t
        WHERE NOT EXISTS (SELECT 1 FROM favorites f WHERE f.trackId = t.id)
        AND NOT EXISTS (SELECT 1 FROM recent_history r WHERE r.trackId = t.id)
        AND NOT EXISTS (SELECT 1 FROM offline_assets o WHERE o.trackId = t.id)
        AND NOT EXISTS (SELECT 1 FROM playlist_items p WHERE p.trackId = t.id)
        AND NOT EXISTS (SELECT 1 FROM listening_sessions s WHERE s.trackId = t.id)
        AND NOT EXISTS (SELECT 1 FROM track_listening s WHERE s.trackId = t.id)
        AND NOT EXISTS (SELECT 1 FROM queue_entries q WHERE q.trackId = t.id)
        AND NOT EXISTS (SELECT 1 FROM lyrics_cache l WHERE l.trackId = t.id AND l.kind != 'UNAVAILABLE')
        ORDER BY t.cachedAtMs DESC, t.id"""
    )
    suspend fun unprotectedTracks(): List<TrackRecord>

    @Query("SELECT * FROM settings WHERE `key` = :key")
    suspend fun setting(key: String): SettingsRecord?

    @Upsert suspend fun putSetting(record: SettingsRecord)
}
