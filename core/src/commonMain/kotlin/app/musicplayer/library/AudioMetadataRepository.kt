package app.musicplayer.library

import app.musicplayer.model.*

interface AudioMetadataRepository {
    suspend fun get(key: AudioMetadataKey): AudioMetadata?

    suspend fun put(key: AudioMetadataKey, metadata: AudioMetadata)
}
