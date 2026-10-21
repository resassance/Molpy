package app.musicplayer.queue

import app.musicplayer.fixtures.Fixtures
import app.musicplayer.model.*
import app.musicplayer.playback.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class QueueControllerTest {
    private val a = Fixtures.track("a", sourceId = "a")
    private val b = Fixtures.track("b", sourceId = "b")
    private val entryA = QueueEntry(QueueEntryId("a-1"), a.id)
    private val entryB = QueueEntry(QueueEntryId("b-1"), b.id)
    private val network =
        NetworkContext(0, NetworkConfig(NetworkMode.SYSTEM, null, null), true, true)

    private fun lease(candidate: PlaybackCandidate) =
        ResolvedPlayback(
            candidate.id,
            Representation("wav", "audio/wav", "pcm", null, 10000, null),
            TransportKind.PROGRESSIVE,
            MediaRequest("https://example.invalid/generated.wav", emptyMap()),
            null,
            0,
        )

    private fun controller(
        scope: CoroutineScope,
        engine: Engine,
        lookup: suspend (TrackId) -> Track? = { id -> listOf(a, b).find { it.id == id } },
        resolve: suspend (PlaybackCandidate) -> AppResult<ResolvedPlayback> = {
            AppResult.Success(lease(it))
        },
    ): QueueController {
        val resolver =
            object : PlaybackResolver {
                override suspend fun resolve(
                    candidate: PlaybackCandidate,
                    capabilities: PlaybackCapabilities,
                    network: NetworkContext,
                ) = resolve(candidate)
            }
        val coordinator = PlaybackCoordinator(engine, resolver, { network }, scope)
        return QueueController(engine, coordinator, lookup).also { queue ->
            scope.launch { coordinator.snapshot.collect { queue.onPlayback(it) } }
        }
    }

    @Test
    fun networkSwitchDuringAutomaticAdvanceRetainsIntentAndCallbackCollector() = runTest {
        var activeNetwork = network
        val firstB = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var bResolutions = 0
        val engine = Engine()
        val resolver =
            object : PlaybackResolver {
                override suspend fun resolve(
                    candidate: PlaybackCandidate,
                    capabilities: PlaybackCapabilities,
                    network: NetworkContext,
                ): AppResult<ResolvedPlayback> {
                    if (candidate.id == b.candidates.first().id && ++bResolutions == 1) {
                        firstB.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    }
                    return AppResult.Success(
                        lease(candidate).copy(networkGeneration = network.generation)
                    )
                }
            }
        val coordinator = PlaybackCoordinator(engine, resolver, { activeNetwork }, backgroundScope)
        val queue = QueueController(engine, coordinator) { id -> listOf(a, b).find { it.id == id } }
        val collector = backgroundScope.launch {
            coordinator.snapshot.collect { queue.onPlayback(it) }
        }
        queue.dispatch(QueueCommand.Add(listOf(entryA, entryB)))
        queue.select(entryA, 0, true)
        runCurrent()
        engine.snapshot.value = engine.snapshot.value.copy(status = PlayerStatus.ENDED)
        runCurrent()
        assertTrue(firstB.isCompleted)
        queue.seek(4321)
        queue.pause()
        activeNetwork = network.copy(generation = 1, ready = false)
        coordinator.networkInvalidated()
        runCurrent()
        assertTrue(cancelled.isCompleted)
        assertTrue(
            collector.isActive,
            "Network invalidation killed the service's real callback collector",
        )
        assertEquals(entryB, coordinator.snapshot.value.current)
        assertEquals(4321L, coordinator.snapshot.value.positionMs)
        assertFalse(coordinator.snapshot.value.playWhenReady)
        activeNetwork = activeNetwork.copy(ready = true)
        coordinator.networkActivated()
        runCurrent()
        assertEquals(entryB, engine.snapshot.value.current)
        assertEquals(4321L, engine.snapshot.value.positionMs)
        assertFalse(engine.snapshot.value.playWhenReady)
        engine.snapshot.value = engine.snapshot.value.copy(positionMs = 5000)
        runCurrent()
        assertEquals(5000L, queue.state.value.positionMs)
        engine.snapshot.value = engine.snapshot.value.copy(status = PlayerStatus.ENDED)
        runCurrent()
        assertNull(engine.snapshot.value.current)
        assertTrue(collector.isActive)
    }

    @Test
    fun addingDoesNotResolveAndSelectingLoadsOnlyCurrentOccurrence() = runTest {
        val engine = Engine()
        val resolved = mutableListOf<CandidateId>()
        val queue =
            controller(
                backgroundScope,
                engine,
                resolve = {
                    resolved += it.id
                    AppResult.Success(lease(it))
                },
            )
        queue.dispatch(QueueCommand.Add(listOf(entryA, entryB)))
        assertTrue(resolved.isEmpty())
        queue.dispatch(QueueCommand.Select(entryA.id))
        assertEquals(listOf(a.candidates.first().id), resolved)
        assertEquals(entryA, engine.snapshot.value.current)
        assertEquals(entryA.id, queue.state.value.currentEntryId)
    }

    @Test
    fun currentRemovalKeepsPausedIntentAndLastNextStopsEngine() = runTest {
        val engine = Engine()
        val queue = controller(backgroundScope, engine)
        queue.dispatch(QueueCommand.Add(listOf(entryA, entryB)))
        queue.select(entryA, 234, false)
        queue.dispatch(QueueCommand.Remove(entryA.id))
        assertEquals(entryB, engine.snapshot.value.current)
        assertFalse(engine.snapshot.value.playWhenReady)
        assertEquals(0L, engine.snapshot.value.positionMs)
        queue.dispatch(QueueCommand.Next)
        assertNull(engine.snapshot.value.current)
        assertFalse(queue.state.value.playWhenReady)
    }

    @Test
    fun previousUsesActualCallbackPositionAndDoesNotResolveRestart() = runTest {
        val engine = Engine()
        var resolutions = 0
        val queue =
            controller(
                backgroundScope,
                engine,
                resolve = {
                    resolutions++
                    AppResult.Success(lease(it))
                },
            )
        queue.dispatch(QueueCommand.Add(listOf(entryA, entryB)))
        queue.select(entryB, 0, true)
        queue.onPlayback(engine.snapshot.value.copy(positionMs = 3001))
        queue.dispatch(QueueCommand.Previous)
        assertEquals(0L, engine.seekPosition)
        assertEquals(entryB.id, queue.state.value.currentEntryId)
        assertEquals(1, resolutions)
        queue.onPlayback(engine.snapshot.value.copy(positionMs = 3000))
        queue.dispatch(QueueCommand.Previous)
        assertEquals(entryA.id, queue.state.value.currentEntryId)
        assertEquals(2, resolutions)
    }

    @Test
    fun missingTrackFailsWithoutSkippingOrResolvingAnotherItem() = runTest {
        val engine = Engine()
        var resolutions = 0
        val queue =
            controller(
                backgroundScope,
                engine,
                lookup = { if (it == b.id) b else null },
                resolve = {
                    resolutions++
                    AppResult.Success(lease(it))
                },
            )
        queue.dispatch(QueueCommand.Add(listOf(entryA, entryB)))
        queue.dispatch(QueueCommand.Select(entryA.id))
        assertEquals(entryA.id, queue.state.value.currentEntryId)
        assertEquals(ErrorCode.SOURCE_UNAVAILABLE, queue.snapshot.value.error?.code)
        assertEquals(PlayerStatus.FAILED, queue.snapshot.value.status)
        assertEquals(0, resolutions)
    }

    @Test
    fun failedResolveDoesNotSkipAndEndedForOldEntryCannotAdvanceCurrent() = runTest {
        val engine = Engine()
        val queue =
            controller(
                backgroundScope,
                engine,
                resolve = { AppResult.Failure(AppError(ErrorCode.NETWORK, "queue.test", true)) },
            )
        queue.dispatch(QueueCommand.Add(listOf(entryA, entryB)))
        queue.select(entryB, 0, true)
        assertEquals(entryB.id, queue.state.value.currentEntryId)
        assertEquals(PlayerStatus.FAILED, queue.snapshot.value.status)
        queue.onPlayback(PlaybackSnapshot(PlayerStatus.ENDED, entryA, a, 10000, 10000, true))
        assertEquals(entryB.id, queue.state.value.currentEntryId)
        assertEquals(PlayerStatus.FAILED, queue.snapshot.value.status)
    }

    @Test
    fun endedAutomaticallyAdvancesButStaleEndedDoesNotAdvanceAgain() = runTest {
        val engine = Engine()
        val queue = controller(backgroundScope, engine)
        queue.dispatch(QueueCommand.Add(listOf(entryA, entryB)))
        queue.select(entryA, 0, true)
        queue.onPlayback(engine.snapshot.value.copy(status = PlayerStatus.ENDED))
        assertEquals(entryB.id, queue.state.value.currentEntryId)
        queue.dispatch(QueueCommand.Ended(entryA.id))
        assertEquals(entryB.id, queue.state.value.currentEntryId)
        queue.onPlayback(engine.snapshot.value.copy(status = PlayerStatus.ENDED))
        assertFalse(queue.state.value.playWhenReady)
        assertNull(engine.snapshot.value.current)
    }

    @Test
    fun newerSelectionCancelsSlowResolveWithoutWaitingForItAndLateResultIsIgnored() = runTest {
        val late = CompletableDeferred<Unit>()
        val engine = Engine()
        val queue =
            controller(
                backgroundScope,
                engine,
                resolve = {
                    if (it.id == a.candidates.first().id)
                        withContext(NonCancellable) { late.await() }
                    AppResult.Success(lease(it))
                },
            )
        queue.dispatch(QueueCommand.Add(listOf(entryA, entryB)))
        val first = launch { queue.select(entryA, 0, true) }
        runCurrent()
        val second = launch { queue.select(entryB, 0, true) }
        runCurrent()
        try {
            assertTrue(second.isCompleted, "new selection blocked behind old resolve")
            assertEquals(entryB, engine.snapshot.value.current)
        } finally {
            late.complete(Unit)
        }
        first.join()
        assertEquals(entryB, engine.snapshot.value.current)
        assertEquals(entryB.id, queue.state.value.currentEntryId)
    }

    @Test
    fun pauseAndSeekDuringResolveChangeFinalLoadIntent() = runTest {
        val ready = CompletableDeferred<Unit>()
        val engine = Engine()
        val queue =
            controller(
                backgroundScope,
                engine,
                resolve = {
                    ready.await()
                    AppResult.Success(lease(it))
                },
            )
        val selecting = launch { queue.select(entryA, 0, true) }
        runCurrent()
        queue.pause()
        queue.seek(4321)
        ready.complete(Unit)
        selecting.join()
        assertFalse(engine.snapshot.value.playWhenReady)
        assertEquals(4321L, engine.snapshot.value.positionMs)
        assertFalse(queue.state.value.playWhenReady)
        assertEquals(4321L, queue.state.value.positionMs)
    }

    @Test
    fun duplicateTrackOccurrencesStayIndependentlySelectable() = runTest {
        val engine = Engine()
        val queue = controller(backgroundScope, engine)
        val duplicate = QueueEntry(QueueEntryId("a-2"), a.id)
        queue.select(entryA, 0, true)
        queue.select(duplicate, 0, true)
        assertEquals(listOf(entryA, duplicate), queue.state.value.entries)
        assertNotEquals(queue.state.value.entries[0].id, queue.state.value.entries[1].id)
        queue.dispatch(QueueCommand.Previous)
        assertEquals(entryA, engine.snapshot.value.current)
    }

    @Test
    fun unknownSelectDoesNotReloadCurrent() = runTest {
        val engine = Engine()
        var resolutions = 0
        val queue =
            controller(
                backgroundScope,
                engine,
                resolve = {
                    resolutions++
                    AppResult.Success(lease(it))
                },
            )
        queue.select(entryA, 1234, true)
        queue.dispatch(QueueCommand.Select(QueueEntryId("missing")))
        assertEquals(1, resolutions)
        assertEquals(1234L, queue.state.value.positionMs)
    }

    @Test
    fun previousRestartsAnOccurrenceWhoseEngineWasStoppedAtQueueBoundary() = runTest {
        val engine = Engine()
        val queue = controller(backgroundScope, engine)
        queue.select(entryA, 5000, true)
        queue.dispatch(QueueCommand.Next)
        assertNull(engine.snapshot.value.current)
        queue.dispatch(QueueCommand.Previous)
        assertEquals(entryA, engine.snapshot.value.current)
        assertEquals(0L, engine.snapshot.value.positionMs)
        assertFalse(engine.snapshot.value.playWhenReady)
    }

    @Test
    fun playRestartsSelectedOccurrenceAfterLastNextStoppedTheEngine() = runTest {
        val engine = Engine()
        val queue = controller(backgroundScope, engine)
        queue.select(entryA, 5000, true)
        queue.dispatch(QueueCommand.Next)
        queue.play()
        assertEquals(entryA, engine.snapshot.value.current)
        assertEquals(0L, engine.snapshot.value.positionMs)
        assertTrue(engine.snapshot.value.playWhenReady)
    }

    @Test
    fun userSelectionDuringAutomaticAdvanceKeepsRealCallbackCollectorAlive() = runTest {
        assertCallbacksSurviveInterruptedAdvance(stopFirst = false)
    }

    @Test
    fun stopDuringAutomaticAdvanceKeepsRealCallbackCollectorAlive() = runTest {
        assertCallbacksSurviveInterruptedAdvance(stopFirst = true)
    }

    @Test
    fun coordinatorOperationCancellationDuringAutomaticAdvanceKeepsCollectorAlive() = runTest {
        val fixture = suspendedAutomaticAdvance()
        fixture.resolution.cancel(CancellationException("Coordinator operation invalidated"))
        runCurrent()
        assertTrue(
            fixture.collector.isActive,
            "Coordinator cancellation terminated the service callback collector",
        )
        assertCallbacksContinue(fixture)
    }

    private suspend fun TestScope.assertCallbacksSurviveInterruptedAdvance(stopFirst: Boolean) {
        val fixture = suspendedAutomaticAdvance()
        if (stopFirst) {
            fixture.queue.stop()
            runCurrent()
            assertTrue(fixture.collector.isActive, "Stop terminated the service callback collector")
            assertNull(fixture.engine.snapshot.value.current)
        }
        assertCallbacksContinue(fixture)
    }

    private suspend fun TestScope.assertCallbacksContinue(fixture: CallbackFixture) {
        fixture.queue.select(fixture.entryC, 0, true)
        runCurrent()
        assertTrue(fixture.cancelled.isCompleted, "Obsolete B resolution was not cancelled")
        assertTrue(
            fixture.collector.isActive,
            "New selection terminated the service callback collector",
        )
        fixture.engine.snapshot.value =
            fixture.engine.snapshot.value.copy(positionMs = 4321, playWhenReady = false)
        runCurrent()
        assertEquals(4321L, fixture.queue.state.value.positionMs)
        assertFalse(fixture.queue.state.value.playWhenReady)
        fixture.engine.snapshot.value =
            fixture.engine.snapshot.value.copy(status = PlayerStatus.ENDED)
        runCurrent()
        assertEquals(fixture.entryD, fixture.engine.snapshot.value.current)
        assertEquals(fixture.entryD.id, fixture.queue.state.value.currentEntryId)
        assertFalse(fixture.engine.snapshot.value.playWhenReady)
        fixture.collector.cancelAndJoin()
    }

    @Test
    fun cancellingServiceCollectorStillCancelsItsAutomaticAdvance() = runTest {
        val fixture = suspendedAutomaticAdvance()
        val ownerCancellation = CancellationException("Service owner stopped")
        var completionCause: Throwable? = null
        fixture.collector.invokeOnCompletion { completionCause = it }
        fixture.collector.cancel(ownerCancellation)
        fixture.collector.join()
        runCurrent()
        assertTrue(fixture.collector.isCancelled)
        assertSame(
            ownerCancellation,
            completionCause,
            "Owner cancellation was replaced or suppressed",
        )
        assertTrue(fixture.cancelled.isCompleted, "Service cancellation did not reach B resolution")
        fixture.queue.select(fixture.entryC, 0, true)
        fixture.engine.snapshot.value = fixture.engine.snapshot.value.copy(positionMs = 4321)
        runCurrent()
        assertEquals(
            0L,
            fixture.queue.state.value.positionMs,
            "Cancelled owner continued collecting callbacks",
        )
    }

    private data class CallbackFixture(
        val queue: QueueController,
        val engine: Engine,
        val collector: Job,
        val cancelled: CompletableDeferred<Unit>,
        val resolution: CompletableDeferred<Unit>,
        val entryC: QueueEntry,
        val entryD: QueueEntry,
    )

    private suspend fun TestScope.suspendedAutomaticAdvance(): CallbackFixture {
        val c = Fixtures.track("c", sourceId = "c")
        val d = Fixtures.track("d", sourceId = "d")
        val entryC = QueueEntry(QueueEntryId("c-1"), c.id)
        val entryD = QueueEntry(QueueEntryId("d-1"), d.id)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val resolution = CompletableDeferred<Unit>()
        val engine = Engine()
        val resolver =
            object : PlaybackResolver {
                override suspend fun resolve(
                    candidate: PlaybackCandidate,
                    capabilities: PlaybackCapabilities,
                    network: NetworkContext,
                ): AppResult<ResolvedPlayback> {
                    if (candidate.id == b.candidates.first().id) {
                        started.complete(Unit)
                        try {
                            resolution.await()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    }
                    return AppResult.Success(lease(candidate))
                }
            }
        val coordinator = PlaybackCoordinator(engine, resolver, { network }, backgroundScope)
        val queue =
            QueueController(engine, coordinator) { id -> listOf(a, b, c, d).find { it.id == id } }
        val collector = backgroundScope.launch {
            coordinator.snapshot.collect { queue.onPlayback(it) }
        }
        queue.dispatch(QueueCommand.Add(listOf(entryA, entryB, entryC, entryD)))
        queue.select(entryA, 0, true)
        runCurrent()
        engine.snapshot.value = engine.snapshot.value.copy(status = PlayerStatus.ENDED)
        runCurrent()
        assertTrue(started.isCompleted, "Actual Ended A did not start B resolution")
        assertEquals(entryB.id, queue.state.value.currentEntryId)
        assertTrue(collector.isActive)
        return CallbackFixture(queue, engine, collector, cancelled, resolution, entryC, entryD)
    }

    private class Engine : PlayerEngine {
        override val snapshot = MutableStateFlow(PlaybackSnapshot())
        var seekPosition = -1L

        override suspend fun load(
            entry: QueueEntry,
            source: PlayableSource,
            positionMs: Long,
            playWhenReady: Boolean,
        ): AppResult<Unit> {
            snapshot.value =
                PlaybackSnapshot(
                    PlayerStatus.BUFFERING,
                    entry,
                    source.track,
                    positionMs,
                    10000,
                    playWhenReady,
                    loadId = (source as? PlayableSource.Remote)?.loadId,
                )
            return AppResult.Success(Unit)
        }

        override fun play() {
            snapshot.value = snapshot.value.copy(playWhenReady = true)
        }

        override fun pause() {
            snapshot.value = snapshot.value.copy(playWhenReady = false)
        }

        override fun seek(positionMs: Long) {
            seekPosition = positionMs
            snapshot.value = snapshot.value.copy(positionMs = positionMs)
        }

        override fun stop() {
            snapshot.value = PlaybackSnapshot()
        }

        override fun release() {}
    }

    @Test
    fun metadataReadFailureSettlesSelectionAndRetryCanRecover() = runTest {
        var broken = true
        val engine = Engine()
        val queue =
            controller(
                backgroundScope,
                engine,
                lookup = {
                    if (broken) throw IllegalStateException("private database failure") else a
                },
            )
        val failed = queue.select(entryA, 0, true)
        assertEquals(ErrorCode.DATABASE, assertIs<AppResult.Failure>(failed).error.code)
        assertEquals(PlayerStatus.FAILED, queue.snapshot.value.status)
        assertEquals(entryA, queue.snapshot.value.current)
        broken = false
        assertIs<AppResult.Success<Unit>>(queue.select(entryA, 0, true))
        assertEquals(a, queue.snapshot.value.track)
    }
}
