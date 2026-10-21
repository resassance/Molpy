package app.musicplayer.playback

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.musicplayer.listening.*
import app.musicplayer.model.*
import app.musicplayer.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class ListeningServiceTest {
    @Test
    fun nativePlayingTimeSurvivesPresentationRebindingAndOwnerTeardown() = runBlocking {
        GeneratedMedia().use { media ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = java.io.File.createTempFile("listening-", ".db", context.cacheDir)
            val database =
                androidx.room.Room.databaseBuilder<MusicDatabase>(context, file.absolutePath)
                    .setDriver(androidx.sqlite.driver.bundled.BundledSQLiteDriver())
                    .build()
            val repository = RoomListeningRepository(database, System::currentTimeMillis)
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val http = okhttp3.OkHttpClient()
            val engine = withContext(Dispatchers.Main) { AndroidPlayerEngine(context) { http } }
            val source = SourceRef("fixture", "generated", "listening")
            val candidate =
                PlaybackCandidate(CandidateId("listening"), source, "", 10000, MatchConfidence.HIGH)
            val track =
                Track(
                    TrackId("listening"),
                    "Generated audio",
                    listOf(ArtistRef("Fixture", null)),
                    null,
                    Artwork(ArtworkKind.PLACEHOLDER, null, null),
                    10000,
                    setOf(source),
                    MetadataProvenance(source, 0, false),
                    listOf(candidate),
                )
            val resolver =
                object : PlaybackResolver {
                    override suspend fun resolve(
                        candidate: PlaybackCandidate,
                        capabilities: PlaybackCapabilities,
                        network: NetworkContext,
                    ) =
                        AppResult.Success(
                            ResolvedPlayback(
                                candidate.id,
                                Representation("wav", "audio/wav", "pcm", null, 10000, null),
                                TransportKind.PROGRESSIVE,
                                MediaRequest(media.uri, emptyMap()),
                                null,
                                0,
                            )
                        )
                }
            val network =
                NetworkContext(0, NetworkConfig(NetworkMode.SYSTEM, null, null), true, true)
            val coordinator = PlaybackCoordinator(engine, resolver, { network }, owner)
            val tracker =
                ListeningTracker(
                    repository,
                    coordinator.snapshot,
                    owner,
                    SystemClock::elapsedRealtime,
                    System::currentTimeMillis,
                )
            try {
                withContext(Dispatchers.Main) {
                    coordinator.select(
                        track,
                        QueueEntry(QueueEntryId("listening-entry"), track.id),
                        0,
                        true,
                    )
                }
                withTimeout(15000) {
                    coordinator.snapshot.first {
                        it.status == PlayerStatus.PLAYING && it.positionMs >= 1250
                    }
                }
                repeat(2) {
                    val presentation = owner.launch { coordinator.snapshot.collect {} }
                    yield()
                    presentation.cancelAndJoin()
                }
                withContext(Dispatchers.Main) { coordinator.pause() }
                withTimeout(5000) {
                    coordinator.snapshot.first { it.status == PlayerStatus.PAUSED }
                }
                withContext(Dispatchers.Main) {
                    tracker.ownerStopped()
                    owner.cancel()
                    tracker.close()
                }
                val profile = (repository.profile.first() as AppResult.Success).value
                assertTrue(profile.totalMs in 500L..10000L)
                assertEquals(1, (repository.history() as AppResult.Success).value.items.size)
                assertEquals(track.id, profile.tracks.single().track.id)
            } finally {
                withContext(Dispatchers.Main) {
                    tracker.ownerStopped()
                    owner.cancel()
                    tracker.close()
                    coordinator.release()
                }
                database.close()
                file.delete()
                http.dispatcher.executorService.shutdown()
                http.connectionPool.evictAll()
            }
        }
    }
}
