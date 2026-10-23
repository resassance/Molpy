package app.musicplayer.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ListeningDao {
    @Query(
        """SELECT (SELECT COUNT(*) FROM track_listening) + (SELECT COUNT(*) FROM artist_listening)
        + (SELECT COUNT(*) FROM listening_sessions) + (SELECT COUNT(*) FROM tracks) + (SELECT COUNT(*) FROM artists) + (SELECT COUNT(*) FROM artist_aliases) + (SELECT COUNT(*) FROM track_artist_overrides) + (SELECT COUNT(*) FROM artist_merges)"""
    )
    fun observeChanges(): Flow<Long>

    @Query("SELECT * FROM track_listening ORDER BY playedMs DESC, lastPlayedAtMs DESC, trackId")
    suspend fun tracks(): List<TrackListeningRecord>

    @Query("SELECT * FROM artist_listening ORDER BY playedMs DESC, lastPlayedAtMs DESC, `key`")
    suspend fun artists(): List<ArtistListeningRecord>

    @Query("SELECT * FROM listening_sessions ORDER BY id")
    suspend fun allSessions(): List<ListeningSessionRecord>

    @Query("SELECT * FROM listening_sessions WHERE id = :id")
    suspend fun session(id: String): ListeningSessionRecord?

    @Upsert suspend fun putSession(record: ListeningSessionRecord)

    @Query("SELECT * FROM track_listening WHERE trackId = :id")
    suspend fun track(id: String): TrackListeningRecord?

    @Upsert suspend fun putTrack(record: TrackListeningRecord)

    @Query("SELECT * FROM artist_listening WHERE `key` = :key")
    suspend fun artist(key: String): ArtistListeningRecord?

    @Upsert suspend fun putArtist(record: ArtistListeningRecord)

    @Query(
        """SELECT * FROM listening_sessions WHERE :hasCursor = 0 OR startedAtMs < :date
        OR (startedAtMs = :date AND id < :id) ORDER BY startedAtMs DESC, id DESC LIMIT :limit"""
    )
    suspend fun history(
        hasCursor: Boolean,
        date: Long,
        id: String,
        limit: Int,
    ): List<ListeningSessionRecord>
}
