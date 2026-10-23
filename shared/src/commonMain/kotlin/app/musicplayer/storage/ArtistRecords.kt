package app.musicplayer.storage

import androidx.room.*
import app.musicplayer.artists.*
import app.musicplayer.model.*

@Entity(tableName = "artists")
data class ArtistProfileRecord(
    @PrimaryKey val id: String,
    val name: String,
    val provider: String?,
    val kind: String?,
    val sourceId: String?,
)

@Entity(
    tableName = "artist_aliases",
    foreignKeys =
        [
            ForeignKey(
                entity = ArtistProfileRecord::class,
                parentColumns = ["id"],
                childColumns = ["artistId"],
                onDelete = ForeignKey.RESTRICT,
            )
        ],
    indices = [Index("artistId")],
)
data class ArtistAliasRecord(@PrimaryKey val key: String, val artistId: String)

@Entity(
    tableName = "track_artist_overrides",
    primaryKeys = ["trackId", "position"],
    foreignKeys =
        [
            ForeignKey(
                entity = TrackRecord::class,
                parentColumns = ["id"],
                childColumns = ["trackId"],
                onDelete = ForeignKey.RESTRICT,
            ),
            ForeignKey(
                entity = ArtistProfileRecord::class,
                parentColumns = ["id"],
                childColumns = ["artistId"],
                onDelete = ForeignKey.RESTRICT,
            ),
        ],
    indices = [Index("artistId"), Index(value = ["trackId", "artistId"], unique = true)],
)
data class TrackArtistOverrideRecord(val trackId: String, val position: Int, val artistId: String)

@Entity(
    tableName = "artist_merges",
    foreignKeys =
        [
            ForeignKey(
                entity = ArtistProfileRecord::class,
                parentColumns = ["id"],
                childColumns = ["originId"],
                onDelete = ForeignKey.RESTRICT,
            ),
            ForeignKey(
                entity = ArtistProfileRecord::class,
                parentColumns = ["id"],
                childColumns = ["targetId"],
                onDelete = ForeignKey.RESTRICT,
            ),
        ],
    indices = [Index("targetId")],
)
data class ArtistMergeRecord(
    @PrimaryKey val originId: String,
    val targetId: String,
    val preferredProvider: String?,
    val preferredKind: String?,
    val preferredSourceId: String?,
    val revision: Long,
)

@Entity(
    tableName = "listening_artist_credits",
    primaryKeys = ["sessionId", "artistId"],
    foreignKeys =
        [
            ForeignKey(
                entity = ListeningSessionRecord::class,
                parentColumns = ["id"],
                childColumns = ["sessionId"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = ArtistProfileRecord::class,
                parentColumns = ["id"],
                childColumns = ["artistId"],
                onDelete = ForeignKey.RESTRICT,
            ),
        ],
    indices = [Index("artistId")],
)
data class SessionArtistRecord(val sessionId: String, val artistId: String)

internal fun artistSource(provider: String?, kind: String?, id: String?): SourceRef? {
    if (provider == null) {
        require(kind == null && id == null)
        return null
    }
    return SourceRef(provider, requireNotNull(kind), requireNotNull(id))
}

internal fun ArtistProfileRecord.domain() =
    ArtistProfile(ArtistId(id), name, artistSource(provider, kind, sourceId))

internal fun ArtistMergeRecord.domain() =
    ArtistMerge(
        ArtistId(originId),
        ArtistId(targetId),
        artistSource(preferredProvider, preferredKind, preferredSourceId),
        revision,
    )
