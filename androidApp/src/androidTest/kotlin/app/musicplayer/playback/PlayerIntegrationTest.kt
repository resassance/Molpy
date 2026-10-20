package app.musicplayer.playback

import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ActivityScenario
import app.musicplayer.MainActivity
import app.musicplayer.MusicApplication
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
            Artwork(ArtworkKind.RELEASE, "https://images.invalid/cover.png", null), null, setOf(ref), MetadataProvenance(ref, 0, false), emptyList())
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
                assertNull("MediaSession must not create an independent remote artwork fetch", result.value.mediaItem.mediaMetadata.artworkUri)
            }
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test fun generatedWavUsesActualDurationSeekPauseAndCallbacks() = runBlocking {
        GeneratedMedia().use { media ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val http = okhttp3.OkHttpClient()
            val engine = withContext(Dispatchers.Main) { AndroidPlayerEngine(context) { http } }
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
                context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
                val disconnected = withTimeout(5000) { engine.snapshot.first { it.status == PlayerStatus.PAUSED } }
                assertFalse("Headset/noisy disconnect must clear play intent", disconnected.playWhenReady)
                withContext(Dispatchers.Main) { engine.play() }
                withTimeout(5000) { engine.snapshot.first { it.status == PlayerStatus.PLAYING } }
                withContext(Dispatchers.Main) { engine.pause() }
                val paused = withTimeout(5000) { engine.snapshot.first { it.status == PlayerStatus.PAUSED } }
                assertFalse(paused.playWhenReady)
                withContext(Dispatchers.Main) { engine.seek(100000) }
                val clamped = withTimeout(5000) { engine.snapshot.first { it.positionMs >= 9900 } }
                assertTrue(clamped.positionMs <= 10100)
            } finally {
                withContext(Dispatchers.Main) { engine.release() }
                http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
            }
        }
    }
    @Test fun activityRecreationAndControllerReconnectKeepServiceOwnedRestoredMetadata() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MusicApplication
        app.stopService(Intent(app, PlaybackService::class.java))
        withTimeout(5000) { app.graph.playback.first { it == null } }
        val ref = SourceRef("test", "generated", "restored")
        val track = Track(TrackId("lifecycle-restored"), "Restored generated metadata", emptyList(), null,
            Artwork(ArtworkKind.PLACEHOLDER, null, null), null, setOf(ref), MetadataProvenance(ref, 0, false), emptyList())
        val entry = QueueEntry(QueueEntryId("lifecycle-occurrence"), track.id)
        app.graph.library.upsert(track)
        app.graph.library.saveQueue(QueueSnapshot(listOf(entry), entry.id, 4321, true))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var connection: PlayerConnection? = null
        try {
            val host = withTimeout(10000) { app.graph.playback.first { it != null } }!!
            assertEquals(PlayerStatus.PAUSED, host.snapshot.value.status)
            assertEquals(4321L, host.snapshot.value.positionMs)
            assertFalse(host.snapshot.value.playWhenReady)
            scenario.recreate()
            assertSame("Activity recreation retains the service owner", host, app.graph.playback.value)
            withContext(Dispatchers.Main) { connection = PlayerConnection(app).also { it.connect() } }
            withTimeout(10000) { connection!!.status.first { it == PlayerConnection.Status.CONNECTED } }
            withTimeout(5000) { connection!!.snapshot.first { it.current == entry } }
            withContext(Dispatchers.Main) { connection!!.close(); connection = PlayerConnection(app).also { it.connect() } }
            withTimeout(10000) { connection!!.status.first { it == PlayerConnection.Status.CONNECTED } }
            val reconnected = withTimeout(5000) { connection!!.snapshot.first { it.current == entry } }
            assertEquals(track, reconnected.track)
            assertEquals(4321L, reconnected.positionMs)
            assertFalse(reconnected.playWhenReady)
            assertSame(host, app.graph.playback.value)
        } finally {
            withContext(Dispatchers.Main) { connection?.close() }
            scenario.close()
            app.stopService(Intent(app, PlaybackService::class.java))
        }
    }

    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    @Test fun nativeHttpCauseRetainsAuthenticationAndRateLimitCategories() {
        val data = androidx.media3.datasource.DataSpec.Builder().setUri("https://example.invalid/media?signature=test-secret").build()
        mapOf(407 to ErrorCode.PROXY_AUTH, 401 to ErrorCode.LOGIN_REQUIRED, 429 to ErrorCode.RATE_LIMIT,
            403 to ErrorCode.URL_REJECTED).forEach { (status, expected) ->
            val http = androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException(status, "test-password", null,
                mapOf("Set-Cookie" to listOf("test-password")), data, byteArrayOf())
            val error = androidx.media3.common.PlaybackException("signature=test-secret", java.io.IOException(http),
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
            assertEquals(expected, normalizeMedia3Failure(error).code)
        }
        assertEquals(ErrorCode.NETWORK, normalizeMedia3Failure(androidx.media3.common.PlaybackException("403 test-password", null,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)).code)
    }

}
