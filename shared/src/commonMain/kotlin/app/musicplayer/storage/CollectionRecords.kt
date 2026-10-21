package app.musicplayer.storage

import androidx.room.*

@Entity(tableName = "playlists")
data class PlaylistRecord(@PrimaryKey val id: String, val name: String, val createdAtMs: Long)

@Entity(
    tableName = "playlist_items",
    foreignKeys =
        [
            ForeignKey(
                entity = PlaylistRecord::class,
                parentColumns = ["id"],
                childColumns = ["playlistId"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = TrackRecord::class,
                parentColumns = ["id"],
                childColumns = ["trackId"],
                onDelete = ForeignKey.RESTRICT,
            ),
        ],
    indices = [Index(value = ["playlistId", "position"], unique = true), Index("trackId")],
)
data class PlaylistItemRecord(
    @PrimaryKey val id: String,
    val playlistId: String,
    val trackId: String,
    val position: Int,
)
