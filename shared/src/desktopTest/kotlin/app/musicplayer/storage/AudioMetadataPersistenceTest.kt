package app.musicplayer.storage

import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class AudioMetadataPersistenceTest {
    @Test
    fun metadataIsKeyedBySourceCandidateAndFormatAndSettingsSurviveReopen() = runBlocking {
        val library = TestLibrary()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            library.open()
            val key =
                AudioMetadataKey(
                    CandidateId("candidate"),
                    SourceRef("youtube", "video", "one"),
                    "140",
                )
            val metadata = RoomAudioMetadataRepository(library.db)
            metadata.put(key, AudioMetadata(6.0))
            assertEquals(AudioMetadata(6.0), metadata.get(key))
            assertNull(metadata.get(key.copy(representationId = "251")))
            assertNull(metadata.get(key.copy(candidateId = CandidateId("other"))))
            assertNull(metadata.get(key.copy(source = key.source.copy(id = "two"))))
            val preferences = RoomPlaybackPreferencesRepository(library.db, owner)
            preferences.setNormalizationEnabled(false)
            library.reopen()
            assertEquals(AudioMetadata(6.0), RoomAudioMetadataRepository(library.db).get(key))
            val reopened = RoomPlaybackPreferencesRepository(library.db, owner)
            assertFalse(withTimeout(5000) { reopened.normalizationEnabled.first { !it } })
            RoomAudioMetadataRepository(library.db).put(key, AudioMetadata(Double.NaN))
            assertNull(RoomAudioMetadataRepository(library.db).get(key)?.relativeLoudnessDb)
        } finally {
            owner.cancel()
            library.close()
        }
    }
}
