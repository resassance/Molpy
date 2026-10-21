package app.musicplayer.storage

import androidx.room.*

@Entity(
    tableName = "listening_sessions",
    foreignKeys =
        [
            ForeignKey(
                entity = TrackRecord::class,
                parentColumns = ["id"],
                childColumns = ["trackId"],
                onDelete = ForeignKey.RESTRICT,
            )
        ],
    indices = [Index("trackId"), Index(value = ["startedAtMs", "id"])],
)
data class ListeningSessionRecord(
    @PrimaryKey val id: String,
    val revision: Long,
    val entryId: String?,
    val trackId: String,
    val startedAtMs: Long,
    val lastPlayedAtMs: Long,
    val playedMs: Long?,
    val creditsJson: String,
)

@Entity(
    tableName = "track_listening",
    foreignKeys =
        [
            ForeignKey(
                entity = TrackRecord::class,
                parentColumns = ["id"],
                childColumns = ["trackId"],
                onDelete = ForeignKey.RESTRICT,
            )
        ],
)
data class TrackListeningRecord(
    @PrimaryKey val trackId: String,
    val playedMs: Long,
    val lastPlayedAtMs: Long,
)

@Entity(tableName = "artist_listening")
data class ArtistListeningRecord(
    @PrimaryKey val key: String,
    val name: String,
    val provider: String?,
    val kind: String?,
    val sourceId: String?,
    val playedMs: Long,
    val lastPlayedAtMs: Long,
)
