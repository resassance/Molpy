package app.musicplayer.desktop.playback

import app.musicplayer.model.*
import java.io.*
import java.util.concurrent.CountDownLatch
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class MpvReviewRegressionTest {
    private class Session : MpvSession {
        var closed = false
        val commands = mutableListOf<List<JsonElement>>()
        override val events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 8)

        override suspend fun command(args: List<JsonElement>): JsonElement {
            commands += args
            return JsonNull
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun queuePauseDuringConnectionReachesInitializingEngine() = runTest {
        val gate = CompletableDeferred<Unit>()
        val session = Session()
        val engine =
            MpvPlayerEngine(backgroundScope) {
                gate.await()
                session
            }
        val candidate =
            PlaybackCandidate(
                CandidateId("c"),
                SourceRef("youtube", "video", "v"),
                "",
                120000,
                MatchConfidence.HIGH,
            )
        val track = fixtureTrack().copy(candidates = listOf(candidate))
        val network = NetworkContext(1, NetworkConfig(NetworkMode.SYSTEM, null, null), true, true)
        val resolver =
            object : app.musicplayer.playback.PlaybackResolver {
                override suspend fun resolve(
                    candidate: PlaybackCandidate,
                    capabilities: PlaybackCapabilities,
                    network: NetworkContext,
                ): AppResult<ResolvedPlayback> =
                    AppResult.Success(
                        ResolvedPlayback(
                            candidate.id,
                            Representation("r", "audio/wav", "pcm", null, 120000, null),
                            TransportKind.PROGRESSIVE,
                            MediaRequest("https://example.invalid/audio", emptyMap()),
                            null,
                            1,
                        )
                    )
            }
        val coordinator =
            app.musicplayer.playback.PlaybackCoordinator(
                engine,
                resolver,
                { network },
                backgroundScope,
            )
        val port = app.musicplayer.desktop.DesktopPlaybackPort(engine, coordinator, backgroundScope)
        val playing = async { port.playList(listOf(track), 0) }
        runCurrent()
        port.pause()
        runCurrent()
        gate.complete(Unit)
        playing.await()
        runCurrent()
        assertFalse(engine.snapshot.value.playWhenReady)
        assertFalse(port.snapshot.value.playWhenReady)
        coordinator.release()
    }

    @Test
    fun disconnectStopsOwnedSessionWithoutLosingFailedSelection() = runTest {
        val session = Session()
        val engine = MpvPlayerEngine(backgroundScope) { session }
        val track = fixtureTrack()
        engine.load(
            QueueEntry(QueueEntryId("e"), track.id),
            PlayableSource.Local(AssetId("a"), "file:///audio", "audio/wav", "k", track, 1),
            0,
            true,
        )
        session.events.emit(buildJsonObject { put("event", "shutdown") })
        runCurrent()
        assertTrue(session.closed)
        assertEquals(PlayerStatus.FAILED, engine.snapshot.value.status)
        assertEquals(track.id, engine.snapshot.value.track?.id)
        engine.release()
    }

    @Test
    fun pauseDuringConnectionIsRetainedInInitialLoad() = runTest {
        val gate = CompletableDeferred<Unit>()
        val session = Session()
        val engine =
            MpvPlayerEngine(backgroundScope) {
                gate.await()
                session
            }
        val track = fixtureTrack()
        val loading = async {
            engine.load(
                QueueEntry(QueueEntryId("e"), track.id),
                PlayableSource.Local(AssetId("a"), "file:///audio", "audio/wav", "k", track, 1),
                0,
                true,
            )
        }
        runCurrent()
        engine.pause()
        gate.complete(Unit)
        loading.await()
        val pause =
            session.commands.last {
                it.first().jsonPrimitive.content == "set_property" &&
                    it.getOrNull(1)?.jsonPrimitive?.content == "pause"
            }
        assertEquals(true, pause[2].jsonPrimitive.boolean)
        assertFalse(engine.snapshot.value.playWhenReady)
        engine.release()
    }

    @Test
    fun cancelledDeliveryClosesAcquiredResource() {
        class Dispatcher : CoroutineDispatcher() {
            val pending = java.util.ArrayDeque<Runnable>()

            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                pending.add(block)
            }

            fun step() {
                pending.removeFirst().run()
            }
        }
        val owner = Dispatcher()
        val worker = Dispatcher()
        val session = Session()
        val scope = CoroutineScope(Job() + owner)
        val acquiring = scope.launch { deliverMpvSession(worker) { session } }
        owner.step()
        worker.step()
        acquiring.cancel()
        while (owner.pending.isNotEmpty()) owner.step()
        assertTrue(session.closed)
        scope.cancel()
    }

    @Test
    fun blockedWriteIsClosedAtDeadline() = runBlocking {
        val input = PipedInputStream()
        val server = PipedOutputStream(input)
        val released = CountDownLatch(1)
        val output =
            object : OutputStream() {
                override fun write(b: Int) {
                    released.await()
                }

                override fun close() {
                    released.countDown()
                }
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val connection = MpvConnection(input, output, scope, timeoutMs = 100)
        java.util.concurrent.CompletableFuture.delayedExecutor(
                1,
                java.util.concurrent.TimeUnit.SECONDS,
            )
            .execute { released.countDown() }
        val started = System.nanoTime()
        try {
            withTimeout(2000) { assertFailsWith<MpvException> { connection.call("play") } }
            assertEquals(0, released.count)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 800)
        } finally {
            connection.close()
            server.close()
            scope.cancel()
        }
    }
}
