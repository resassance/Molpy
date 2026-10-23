package app.musicplayer.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistDao {
    @Query(
        "SELECT (SELECT COUNT(*) FROM artists)+(SELECT COUNT(*) FROM artist_aliases)+(SELECT COUNT(*) FROM track_artist_overrides)+(SELECT COUNT(*) FROM artist_merges)+(SELECT COUNT(*) FROM settings)"
    )
    fun observeDirectory(): Flow<Long>

    @Query("SELECT * FROM artists ORDER BY id") suspend fun profiles(): List<ArtistProfileRecord>

    @Query("SELECT * FROM artist_aliases ORDER BY `key`")
    suspend fun aliases(): List<ArtistAliasRecord>

    @Query("SELECT * FROM track_artist_overrides ORDER BY trackId, position")
    suspend fun overrides(): List<TrackArtistOverrideRecord>

    @Query("SELECT * FROM artist_merges ORDER BY revision, originId")
    suspend fun merges(): List<ArtistMergeRecord>

    @Query("SELECT * FROM listening_artist_credits ORDER BY sessionId, artistId")
    suspend fun credits(): List<SessionArtistRecord>

    @Query("SELECT * FROM artist_aliases WHERE `key`=:key")
    suspend fun alias(key: String): ArtistAliasRecord?

    @Query("SELECT * FROM artists WHERE id=:id")
    suspend fun profile(id: String): ArtistProfileRecord?

    @Query("SELECT * FROM track_artist_overrides WHERE trackId=:id ORDER BY position")
    suspend fun override(id: String): List<TrackArtistOverrideRecord>

    @Query("SELECT * FROM listening_artist_credits WHERE sessionId=:id ORDER BY artistId")
    suspend fun sessionCredits(id: String): List<SessionArtistRecord>

    @Upsert suspend fun putProfile(record: ArtistProfileRecord)

    @Insert suspend fun insertAlias(record: ArtistAliasRecord)

    @Insert suspend fun insertOverrides(records: List<TrackArtistOverrideRecord>)

    @Insert suspend fun insertMerge(record: ArtistMergeRecord)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCredits(records: List<SessionArtistRecord>)

    @Query("DELETE FROM track_artist_overrides WHERE trackId=:id")
    suspend fun deleteOverride(id: String)

    @Query("DELETE FROM artist_merges WHERE originId=:id") suspend fun deleteMerge(id: String)

    @Query("DELETE FROM artist_listening") suspend fun clearListening()
}
