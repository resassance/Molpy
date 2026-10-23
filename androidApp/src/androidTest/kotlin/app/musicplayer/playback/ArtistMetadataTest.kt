package app.musicplayer.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.musicplayer.artists.*
import app.musicplayer.model.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class ArtistMetadataTest {
    @Test
    fun localMetadataUpdateKeepsPlaybackIdentityAndCacheKey() = runBlocking {
        verify(offline = true)
    }

    @Test
    fun streamedMetadataUpdateKeepsResolverLeaseAndBufferedAudio() = runBlocking {
        verify(offline = false)
    }

    private suspend fun verify(offline: Boolean) {
        GeneratedMedia().use { media ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val http = OkHttpClient()
            val calls = AtomicInteger()
            val resolves = AtomicInteger()
            val factory = Call.Factory { request ->
                calls.incrementAndGet()
                http.newCall(request)
            }
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val engine =
                withContext(Dispatchers.Main) {
                    AndroidPlayerEngine(context, { factory }, DefaultDataSource.Factory(context))
                }
            val source = SourceRef("fixture", "generated", "artists")
            val candidate =
                PlaybackCandidate(
                    CandidateId("generated"),
                    source,
                    "Generated",
                    10000,
                    MatchConfidence.HIGH,
                )
            val track =
                Track(
                    TrackId("generated"),
                    "Generated music",
                    listOf(ArtistRef("Original", null)),
                    null,
                    Artwork(ArtworkKind.PLACEHOLDER, null, null),
                    10000,
                    setOf(source),
                    MetadataProvenance(source, 0, false),
                    listOf(candidate),
                )
            val entry = QueueEntry(QueueEntryId("entry"), track.id)
            val a = ArtistId("original")
            val b = ArtistId("corrected")
            val original =
                ArtistDirectory(
                    profiles =
                        mapOf(
                            a to ArtistProfile(a, "Original", null),
                            b to ArtistProfile(b, "Correct", null),
                        ),
                    aliases = mapOf(ArtistResolver.alias(track, 0) to a),
                )
            val directory =
                MutableStateFlow<AppResult<ArtistDirectory>>(AppResult.Success(original))
            val observer =
                ArtistMetadataObserver(engine.snapshot, directory, engine::updateArtists, owner)
            var file: File? = null
            var coordinator: PlaybackCoordinator? = null
            var released = false
            try {
                if (offline) {
                    val audio =
                        withContext(Dispatchers.IO) {
                            http.newCall(Request.Builder().url(media.uri).build()).execute().use {
                                requireNotNull(it.body).bytes()
                            }
                        }
                    val local =
                        File.createTempFile("artist-metadata-", ".wav", context.cacheDir).also {
                            it.writeBytes(audio)
                        }
                    file = local
                    assertTrue(
                        engine.load(
                            entry,
                            PlayableSource.Local(
                                AssetId("local"),
                                local.toURI().toString(),
                                "audio/wav",
                                "stable-cache-key",
                                track,
                                44,
                                candidate.id,
                            ),
                            0,
                            false,
                        ) is AppResult.Success
                    )
                } else {
                    val resolver =
                        object : PlaybackResolver {
                            override suspend fun resolve(
                                candidate: PlaybackCandidate,
                                capabilities: PlaybackCapabilities,
                                network: NetworkContext,
                            ): AppResult<ResolvedPlayback> {
                                resolves.incrementAndGet()
                                return AppResult.Success(
                                    ResolvedPlayback(
                                        candidate.id,
                                        Representation(
                                            "wav",
                                            "audio/wav",
                                            "pcm",
                                            null,
                                            10000,
                                            null,
                                        ),
                                        TransportKind.PROGRESSIVE,
                                        MediaRequest(
                                            media.uri,
                                            mapOf("X-Molpy-Fixture" to "generated"),
                                        ),
                                        null,
                                        0,
                                    )
                                )
                            }
                        }
                    coordinator =
                        withContext(Dispatchers.Main) {
                            PlaybackCoordinator(
                                engine,
                                resolver,
                                {
                                    NetworkContext(
                                        0,
                                        NetworkConfig(NetworkMode.SYSTEM, null, null),
                                        true,
                                        true,
                                    )
                                },
                                owner,
                            )
                        }
                    assertTrue(
                        withContext(Dispatchers.Main) { coordinator.select(track, entry, 0, false) }
                            is AppResult.Success
                    )
                }
                withTimeout(15000) {
                    engine.snapshot.first {
                        it.status == PlayerStatus.PAUSED && it.durationMs != null
                    }
                }
                withContext(Dispatchers.Main) { engine.seek(3000) }
                withTimeout(10000) { engine.snapshot.first { it.positionMs >= 3000 } }
                val before = engine.snapshot.value
                val beforeItem =
                    withContext(Dispatchers.Main) { requireNotNull(engine.player.currentMediaItem) }
                val beforeCalls = calls.get()
                directory.value =
                    AppResult.Success(
                        original.copy(revision = 2, overrides = mapOf(track.id to listOf(b)))
                    )
                withContext(Dispatchers.Main) {
                    withTimeout(5000) {
                        while (
                            engine.player.currentMediaItem?.mediaMetadata?.artist?.toString() !=
                                "Correct"
                        ) delay(10)
                    }
                    withTimeout(5000) {
                        while (engine.player.mediaMetadata.artist?.toString() != "Correct") delay(
                            10
                        )
                    }
                    val after = engine.snapshot.value
                    val afterItem = requireNotNull(engine.player.currentMediaItem)
                    assertEquals(before.loadId, after.loadId)
                    assertEquals(before.current, after.current)
                    assertEquals(before.candidateId, after.candidateId)
                    assertEquals(track, after.track)
                    assertTrue(after.positionMs >= before.positionMs)
                    assertEquals(beforeItem.localConfiguration, afterItem.localConfiguration)
                    assertEquals(beforeItem.mediaId, afterItem.mediaId)
                    assertEquals(beforeItem.mediaMetadata.title, afterItem.mediaMetadata.title)
                    assertEquals(
                        beforeItem.mediaMetadata.albumTitle,
                        afterItem.mediaMetadata.albumTitle,
                    )
                    assertTrue((after.bufferedPositionMs ?: 0) >= (before.bufferedPositionMs ?: 0))
                    val load = requireNotNull(after.loadId)
                    assertFalse(
                        engine.updateArtists(TrackId("old"), entry.id, load, listOf("Stale"))
                    )
                    assertFalse(
                        engine.updateArtists(track.id, QueueEntryId("old"), load, listOf("Stale"))
                    )
                    assertFalse(engine.updateArtists(track.id, entry.id, load + 1, listOf("Stale")))
                    assertEquals(
                        "Correct",
                        engine.player.currentMediaItem?.mediaMetadata?.artist?.toString(),
                    )
                }
                delay(200)
                assertEquals(beforeCalls, calls.get())
                assertEquals(if (offline) 0 else 1, resolves.get())
                withContext(Dispatchers.Main) { engine.play() }
                withTimeout(10000) {
                    engine.snapshot.first {
                        it.status == PlayerStatus.PLAYING && it.positionMs > 3000
                    }
                }
                withContext(Dispatchers.Main) {
                    observer.close()
                    if (coordinator != null) coordinator.release() else engine.release()
                    released = true
                    assertFalse(
                        engine.updateArtists(
                            track.id,
                            entry.id,
                            requireNotNull(before.loadId),
                            listOf("Released"),
                        )
                    )
                }
            } finally {
                withContext(Dispatchers.Main) {
                    observer.close()
                    if (!released) {
                        if (coordinator != null) coordinator.release() else engine.release()
                    }
                    owner.cancel()
                }
                file?.delete()
                http.dispatcher.executorService.shutdown()
                http.connectionPool.evictAll()
            }
        }
    }

    @Test
    fun pinnedSourcesAcceptMetadataWithUnchangedConfiguration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val http = OkHttpClient()
        try {
            val ref = SourceRef("fixture", "generated", "metadata")
            val track =
                Track(
                    TrackId("metadata"),
                    "Title",
                    emptyList(),
                    null,
                    Artwork(ArtworkKind.PLACEHOLDER, null, null),
                    null,
                    setOf(ref),
                    MetadataProvenance(ref, 0, false),
                    emptyList(),
                )
            val entry = QueueEntry(QueueEntryId("metadata-entry"), track.id)
            for (transport in listOf(TransportKind.PROGRESSIVE, TransportKind.HLS)) {
                val mime =
                    if (transport == TransportKind.HLS) "application/vnd.apple.mpegurl"
                    else "audio/mp4"
                val lease =
                    ResolvedPlayback(
                        CandidateId("media"),
                        Representation("media", mime, "", null, null, null),
                        transport,
                        MediaRequest("https://example.invalid/media", emptyMap()),
                        null,
                        0,
                    )
                val result =
                    MediaSourceFactory(http).create(entry, PlayableSource.Remote(lease, track, 4))
                val mediaSource = (result as AppResult.Success).value
                val before = mediaSource.mediaItem
                val changed =
                    before
                        .buildUpon()
                        .setMediaMetadata(
                            before.mediaMetadata.buildUpon().setArtist("Updated").build()
                        )
                        .build()
                assertTrue(mediaSource.canUpdateMediaItem(changed))
                mediaSource.updateMediaItem(changed)
                assertEquals(before.localConfiguration, mediaSource.mediaItem.localConfiguration)
                assertEquals("Updated", mediaSource.mediaItem.mediaMetadata.artist?.toString())
            }
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
