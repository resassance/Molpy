package app.musicplayer.desktop.playback

import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class MpvPlayerEngineTest {
    private class Session : MpvSession {
        var closed = false
        val commands = mutableListOf<List<JsonElement>>()
        override val events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 10)

        override suspend fun command(args: List<JsonElement>): JsonElement {
            commands += args
            return JsonNull
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun replacingLoadClosesOldSessionAndReleaseStopsCurrent() = runTest {
        val sessions = mutableListOf<Session>()
        val engine = MpvPlayerEngine(backgroundScope) { Session().also(sessions::add) }
        val track = fixtureTrack()
        val entry = QueueEntry(QueueEntryId("entry"), track.id)
        fun source(id: Long) =
            PlayableSource.Local(
                AssetId("asset"),
                "file:///tmp/audio.wav",
                "audio/wav",
                "key",
                track,
                id,
            )
        assertTrue(engine.load(entry, source(1), 0, true) is AppResult.Success)
        assertTrue(engine.load(entry, source(2), 1000, false) is AppResult.Success)
        val load =
            sessions.last().commands.single { it.first().jsonPrimitive.content == "loadfile" }
        assertTrue(load.last().jsonObject.getValue("start").jsonPrimitive.isString)
        assertTrue(sessions.first().closed)
        sessions
            .first()
            .events
            .emit(
                buildJsonObject {
                    put("event", "end-file")
                    put("reason", "error")
                }
            )
        runCurrent()
        assertEquals(2, engine.snapshot.value.loadId)
        assertNotEquals(PlayerStatus.FAILED, engine.snapshot.value.status)
        engine.release()
        engine.release()
        assertTrue(sessions.last().closed)
        assertEquals(PlayerStatus.IDLE, engine.snapshot.value.status)
    }

    @Test
    fun launchFailureIsTypedAndDoesNotExposeCause() = runTest {
        val engine =
            MpvPlayerEngine(backgroundScope) { throw IllegalStateException("private-token") }
        val track = fixtureTrack()
        val result =
            engine.load(
                QueueEntry(QueueEntryId("e"), track.id),
                PlayableSource.Local(AssetId("a"), "private-url", "audio/wav", "x", track, 1),
                0,
                true,
            )
        assertTrue(result is AppResult.Failure)
        assertFalse(result.toString().contains("private"))
        engine.release()
    }
}

internal fun fixtureTrack(): Track =
    Track(
        TrackId("t"),
        "Song",
        listOf(ArtistRef("Artist", null)),
        null,
        Artwork(ArtworkKind.PLACEHOLDER, null, null),
        120000,
        emptySet(),
        MetadataProvenance(SourceRef("youtube_music", "song", "t"), 0, false),
        emptyList(),
    )
