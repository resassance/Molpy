package app.musicplayer.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerIntegrationTest {
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    @Test fun hlsAliasesCreateActualHlsMediaSources() {
        val ref = SourceRef("test", "generated", "hls")
        val track = Track(TrackId("hls"), "Generated HLS metadata", emptyList(), null,
            Artwork(ArtworkKind.PLACEHOLDER, null, null), null, setOf(ref), MetadataProvenance(ref, 0, false), emptyList())
        val entry = QueueEntry(QueueEntryId("hls-entry"), track.id)
        val http = okhttp3.OkHttpClient()
        try {
            val factory = MediaSourceFactory(http)
            listOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl",
                "application/vnd.apple.mpegurl; charset=utf-8").forEach { alias ->
                val lease = ResolvedPlayback(CandidateId("hls"), Representation("hls", alias, "", null, null, null),
                    TransportKind.HLS, MediaRequest("https://example.invalid/manifest.m3u8", emptyMap()), null, 0)
                val result = factory.create(entry, PlayableSource.Remote(lease, track))
                assertTrue(result is AppResult.Success)
                assertTrue(alias, (result as AppResult.Success).value is androidx.media3.exoplayer.hls.HlsMediaSource)
            }
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test fun generatedWavUsesActualDurationSeekPauseAndCallbacks() = runBlocking {
        GeneratedMedia().use { media ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val engine = withContext(Dispatchers.Main) { AndroidPlayerEngine(context) }
            val ref = SourceRef("test", "generated", "sine")
            val track = Track(TrackId("sine"), "Generated sine", listOf(ArtistRef("Molpy test", null)), null,
                Artwork(ArtworkKind.PLACEHOLDER, null, null), 900000, setOf(ref), MetadataProvenance(ref, 0, false), emptyList())
            val entry = QueueEntry(QueueEntryId("sine-entry"), track.id)
            val lease = ResolvedPlayback(CandidateId("sine"), Representation("wav", "audio/wav", "pcm", null, null, null),
                TransportKind.PROGRESSIVE, MediaRequest(media.uri, emptyMap()), null, 0)
            try {
                assertTrue(engine.load(entry, PlayableSource.Remote(lease, track), 0, false) is AppResult.Success)
                val ready = withTimeout(15000) { engine.snapshot.first { it.status == PlayerStatus.PAUSED && it.durationMs != null } }
                assertTrue(ready.durationMs!! in 9900L..10100L)
                withContext(Dispatchers.Main) { engine.seek(5000); engine.play() }
                val playing = withTimeout(10000) { engine.snapshot.first { it.status == PlayerStatus.PLAYING && it.positionMs >= 5000 } }
                assertTrue(playing.positionMs >= 5000)
                assertEquals(track, playing.track)
                withContext(Dispatchers.Main) { engine.pause() }
                val paused = withTimeout(5000) { engine.snapshot.first { it.status == PlayerStatus.PAUSED } }
                assertFalse(paused.playWhenReady)
                withContext(Dispatchers.Main) { engine.seek(100000) }
                val clamped = withTimeout(5000) { engine.snapshot.first { it.positionMs >= 9900 } }
                assertTrue(clamped.positionMs <= 10100)
            } finally { withContext(Dispatchers.Main) { engine.release() } }
        }
    }
}
