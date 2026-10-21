package app.musicplayer.storage

import app.musicplayer.library.AudioMetadataRepository
import app.musicplayer.model.*

class RoomAudioMetadataRepository(database: MusicDatabase) : AudioMetadataRepository {
    private val dao = database.audioMetadataDao()

    override suspend fun get(key: AudioMetadataKey): AudioMetadata? =
        dao.get(
                key.candidateId.value,
                key.source.provider,
                key.source.kind,
                key.source.id,
                key.representationId,
            )
            ?.let { AudioMetadata.fromSourceOffset(it.relativeLoudnessDb) }

    override suspend fun put(key: AudioMetadataKey, metadata: AudioMetadata) {
        val value = AudioMetadata.fromSourceOffset(metadata.relativeLoudnessDb)
        dao.put(
            AudioMetadataRecord(
                key.candidateId.value,
                key.source.provider,
                key.source.kind,
                key.source.id,
                key.representationId,
                value.relativeLoudnessDb,
            )
        )
    }
}
