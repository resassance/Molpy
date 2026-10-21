package app.musicplayer.playback

import app.musicplayer.diagnostics.DiagnosticEvent
import app.musicplayer.fixtures.Fixtures
import app.musicplayer.library.AudioMetadataRepository
import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AudioMetadataRecorderTest {
    @Test
    fun persistenceDoesNotDelayPlaybackAndFailureIsRedacted() = runTest {
        val track = Fixtures.track("record")
        val candidate = track.candidates.first()
        val gate = CompletableDeferred<Unit>()
        val stored = mutableListOf<AudioMetadataKey>()
        val events = mutableListOf<DiagnosticEvent>()
        val repository =
            object : AudioMetadataRepository {
                override suspend fun get(key: AudioMetadataKey): AudioMetadata? = null

                override suspend fun put(key: AudioMetadataKey, metadata: AudioMetadata) {
                    stored += key
                    gate.await()
                    error("credentials=private")
                }
            }
        val recorder =
            AudioMetadataRecorder(
                repository,
                backgroundScope,
                events::add,
                StandardTestDispatcher(testScheduler),
            )
        val lease =
            ResolvedPlayback(
                candidate.id,
                Representation("140", "audio/mp4", "aac", null, null, null),
                TransportKind.PROGRESSIVE,
                MediaRequest("https://media.test/temporary", emptyMap()),
                null,
                0,
                AudioMetadata(6.0),
            )
        recorder.record(candidate, lease)
        runCurrent()
        assertEquals(listOf(AudioMetadataKey(candidate.id, candidate.source, "140")), stored)
        assertTrue(events.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(ErrorCode.DATABASE, events.single().errorCode)
        assertFalse(events.toString().contains("private"))
        assertFalse(events.toString().contains("https://"))
    }
}
