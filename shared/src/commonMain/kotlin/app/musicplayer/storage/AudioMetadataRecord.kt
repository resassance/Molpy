package app.musicplayer.storage

import androidx.room.Entity

@Entity(
    tableName = "audio_metadata",
    primaryKeys = ["candidateId", "provider", "kind", "sourceId", "representationId"],
)
data class AudioMetadataRecord(
    val candidateId: String,
    val provider: String,
    val kind: String,
    val sourceId: String,
    val representationId: String,
    val relativeLoudnessDb: Double?,
)
