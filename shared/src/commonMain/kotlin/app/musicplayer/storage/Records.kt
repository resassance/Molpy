package app.musicplayer.storage

import androidx.room.*
import kotlinx.serialization.Serializable

/**
 * Versioned app fields; provider response bodies and resolved media URLs never enter this schema.
 */
@Entity(tableName = "tracks")
@Serializable
data class TrackRecord(
    @PrimaryKey val id: String,
    val recordVersion: Int,
    val title: String,
    val artistsJson: String,
    val albumJson: String?,
    val artworkKind: String,
    val artworkUrl: String?,
    val artworkLocalKey: String?,
    val durationMs: Long?,
    val provenanceProvider: String,
    val provenanceKind: String,
    val provenanceId: String,
    val fetchedAtMs: Long,
    val tentative: Boolean,
    val cachedAtMs: Long,
)

// Identity outlives evictable metadata, so getOrCreate remains stable after a cache miss.
@Entity(
    tableName = "external_identity",
    primaryKeys = ["provider", "kind", "sourceId"],
    indices = [Index("trackId")],
)
@Serializable
data class ExternalIdentityRecord(
    val provider: String,
    val kind: String,
    val sourceId: String,
    val trackId: String,
)

@Entity(
    tableName = "candidates",
    primaryKeys = ["trackId", "candidateId"],
    foreignKeys =
        [
            ForeignKey(
                entity = TrackRecord::class,
                parentColumns = ["id"],
                childColumns = ["trackId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index(value = ["trackId", "position"], unique = true)],
)
@Serializable
data class CandidateRecord(
    val trackId: String,
    val candidateId: String,
    val position: Int,
    val provider: String,
    val kind: String,
    val sourceId: String,
    val version: String,
    val durationMs: Long?,
    val confidence: String,
)

@Entity(
    tableName = "favorites",
    foreignKeys =
        [
            ForeignKey(
                entity = TrackRecord::class,
                parentColumns = ["id"],
                childColumns = ["trackId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class FavoriteRecord(@PrimaryKey val trackId: String, val addedAtMs: Long)

@Entity(
    tableName = "recent_history",
    foreignKeys =
        [
            ForeignKey(
                entity = TrackRecord::class,
                parentColumns = ["id"],
                childColumns = ["trackId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class RecentRecord(@PrimaryKey val trackId: String, val lastPlayedAtMs: Long)

@Entity(
    tableName = "queue_entries",
    indices = [Index(value = ["position"], unique = true), Index("trackId")],
)
data class QueueEntryRecord(@PrimaryKey val entryId: String, val trackId: String, val position: Int)

@Entity(tableName = "queue_snapshot")
data class QueueSnapshotRecord(
    @PrimaryKey val singletonId: Int = 1,
    val currentEntryId: String?,
    val positionMs: Long,
    val playWhenReady: Boolean,
    @ColumnInfo(defaultValue = "0") val shuffleEnabled: Boolean = false,
    val traversalJson: String? = null,
    val sourceJson: String? = null,
    @ColumnInfo(defaultValue = "0") val revision: Long = 0,
)

@Entity(tableName = "lyrics_cache", primaryKeys = ["trackId", "candidateId"])
data class LyricsRecord(
    val trackId: String,
    val candidateId: String,
    val recordVersion: Int,
    val kind: String,
    val payloadJson: String?,
    val quality: Int,
    val cachedAtMs: Long,
)

@Entity(tableName = "catalog_cache", primaryKeys = ["query", "musicOnly"])
data class CatalogCacheRecord(
    val query: String,
    val musicOnly: Boolean,
    val trackIdsJson: String,
    val cachedAtMs: Long,
)

@Entity(tableName = "settings")
data class SettingsRecord(@PrimaryKey val key: String, val value: String)
