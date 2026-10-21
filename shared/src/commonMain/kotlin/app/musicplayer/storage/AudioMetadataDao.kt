package app.musicplayer.storage

import androidx.room.*

@Dao
interface AudioMetadataDao {
    @Query(
        "SELECT * FROM audio_metadata WHERE candidateId = :candidateId AND provider = :provider AND kind = :kind AND sourceId = :sourceId AND representationId = :representationId"
    )
    suspend fun get(
        candidateId: String,
        provider: String,
        kind: String,
        sourceId: String,
        representationId: String,
    ): AudioMetadataRecord?

    @Upsert suspend fun put(record: AudioMetadataRecord)
}
