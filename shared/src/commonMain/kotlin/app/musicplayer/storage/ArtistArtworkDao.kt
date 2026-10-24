package app.musicplayer.storage

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistArtworkDao {
    @Query("SELECT * FROM artist_artwork WHERE sourceKey=:key")
    fun observe(key: String): Flow<ArtistArtworkRecord?>

    @Query("SELECT * FROM artist_artwork WHERE sourceKey=:key")
    suspend fun get(key: String): ArtistArtworkRecord?

    @Upsert suspend fun put(record: ArtistArtworkRecord)

    @Query("SELECT COUNT(*) FROM artist_artwork") suspend fun count(): Int

    @Query(
        "DELETE FROM artist_artwork WHERE sourceKey NOT IN (SELECT sourceKey FROM artist_artwork ORDER BY checkedAtMs DESC, sourceKey ASC LIMIT 256)"
    )
    suspend fun trim()
}
