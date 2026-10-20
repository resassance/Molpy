package app.musicplayer.storage

import androidx.room.*
import app.musicplayer.downloads.*
import app.musicplayer.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** No request URI, headers, credentials, retries or scheduling state are persisted here. */
@Entity(tableName = "offline_assets",
    foreignKeys = [ForeignKey(entity = TrackRecord::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["trackId", "candidateId", "representationId"], unique = true), Index(value = ["storageKey"], unique = true)])
data class OfflineRecord(
    @PrimaryKey val id: String,
    val trackId: String,
    val snapshotJson: String,
    val candidateId: String,
    val provider: String,
    val kind: String,
    val sourceId: String,
    val representationId: String,
    val mimeType: String,
    val codec: String,
    val expectedBytes: Long?,
    val expectedDurationMs: Long?,
    val validator: String?,
    val storageKey: String,
    val bytes: Long,
    val state: String,
)

internal object OfflineRecordMapper {
    private val json = Json { ignoreUnknownKeys = true }
    @Serializable private data class Snapshot(
        val track: TrackRecord,
        val identities: List<ExternalIdentityRecord>,
        val candidates: List<CandidateRecord>,
    )

    fun record(asset: OfflineAsset): OfflineRecord {
        val track = asset.trackSnapshot
        val snapshot = Snapshot(RecordMapper.track(track, track.id, 0),
            track.externalIds.map { ExternalIdentityRecord(it.provider, it.kind, it.id, track.id.value) },
            RecordMapper.candidates(track, track.id))
        val format = asset.representation
        return OfflineRecord(asset.id.value, asset.trackId.value, json.encodeToString(snapshot), asset.candidateId.value,
            asset.source.provider, asset.source.kind, asset.source.id, format.id, format.mimeType, format.codec,
            format.expectedBytes, format.expectedDurationMs, format.validator, asset.storageKey, asset.bytes, asset.state.name)
    }

    fun asset(record: OfflineRecord): OfflineAsset {
        val snapshot = json.decodeFromString<Snapshot>(record.snapshotJson)
        return OfflineAsset(AssetId(record.id), TrackId(record.trackId),
            RecordMapper.track(snapshot.track, snapshot.identities, snapshot.candidates), CandidateId(record.candidateId),
            SourceRef(record.provider, record.kind, record.sourceId),
            Representation(record.representationId, record.mimeType, record.codec, record.expectedBytes, record.expectedDurationMs, record.validator),
            record.storageKey, record.bytes, DownloadState.valueOf(record.state))
    }
}
