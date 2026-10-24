package app.musicplayer.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "artist_artwork")
data class ArtistArtworkRecord(
    @PrimaryKey val sourceKey: String,
    val provider: String,
    val kind: String,
    val sourceId: String,
    val artworkJson: String?,
    val checkedAtMs: Long,
    val refreshAfterMs: Long,
    val outcome: String,
)
