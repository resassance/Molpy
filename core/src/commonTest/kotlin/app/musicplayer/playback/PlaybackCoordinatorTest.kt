package app.musicplayer.playback

import app.musicplayer.fixtures.Fixtures
import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCoordinatorTest {
    private val network = NetworkContext(0, NetworkConfig(NetworkMode.SYSTEM, null, null), true, true)
    private val a = Fixtures.track("a", sourceId = "a")
    private val b = Fixtures.track("b", sourceId = "b")
    private fun entry(track: Track) = QueueEntry(QueueEntryId(track.id.value), track.id)
    private fun lease(candidate: PlaybackCandidate) = ResolvedPlayback(candidate.id,
        Representation("wav", "audio/wav", "pcm", null, 10_000, null), TransportKind.PROGRESSIVE,
        MediaRequest("https://example.invalid/generated.wav", emptyMap()), null, 0)
    private fun resolver(block: suspend (PlaybackCandidate) -> AppResult<ResolvedPlayback>) = object : PlaybackResolver {
        override suspend fun resolve(candidate: PlaybackCandidate, capabilities: PlaybackCapabilities, network: NetworkContext) = block(candidate)
    }

    @Test fun reconnectRetainsTrackPositionAndLatestPauseIntent() = runTest {
        var context = network
        var resolutions = 0
        val engine = CallbackEngine()
        val coordinator = PlaybackCoordinator(engine, resolver {
            resolutions++
            AppResult.Success(lease(it).copy(networkGeneration = context.generation))
        }, { context }, backgroundScope)
        coordinator.select(a, entry(a), 1200, true)
        engine.callback(PlayerStatus.PLAYING, position = 3456, duration = 10000)
        runCurrent()
        context = network.copy(generation = 1, ready = false)
        coordinator.networkInvalidated()
        assertEquals(a, coordinator.snapshot.value.track)
        assertEquals(3456, coordinator.snapshot.value.positionMs)
        assertEquals(PlayerStatus.RESOLVING, coordinator.snapshot.value.status)
        coordinator.pause()
        context = context.copy(ready = true)
        coordinator.networkActivated()
        assertEquals(2, resolutions)
        assertEquals(3456, coordinator.snapshot.value.positionMs)
        assertFalse(engine.loadedPlayWhenReady)
    }

    @Test fun lateResolveIsCancelledAcrossNetworkChange() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = CallbackEngine()
        val coordinator = PlaybackCoordinator(engine, resolver {
            withContext(NonCancellable) { gate.await() }
            AppResult.Success(lease(it))
        }, { network }, backgroundScope)
        val selection = launch { coordinator.select(a, entry(a), 2345, true) }
        runCurrent()
        coordinator.networkInvalidated()
        gate.complete(Unit); selection.join(); runCurrent()
        assertNull(engine.loadedTrack)
        assertEquals(a, coordinator.snapshot.value.track)
        assertEquals(2345, coordinator.snapshot.value.positionMs)
        assertTrue(coordinator.snapshot.value.playWhenReady)
    }

    @Test fun lateAAfterBDoesNotReplaceB() = runTest {
        val lateA = CompletableDeferred<Unit>()
        val engine = CallbackEngine()
        val coordinator = PlaybackCoordinator(engine, resolver { candidate ->
            if (candidate == a.candidates.first()) withContext(NonCancellable) { lateA.await() }
            AppResult.Success(lease(candidate))
        }, { network }, backgroundScope)
        val first = launch { coordinator.select(a, entry(a), 0, true) }
        runCurrent()
        coordinator.select(b, entry(b), 0, true)
        lateA.complete(Unit)
        first.join()
        runCurrent()
        assertEquals(entry(b), coordinator.snapshot.value.current)
        assertEquals(b, engine.loadedTrack)
    }

    @Test fun pauseDuringResolveChangesLoadIntent() = runTest {
        val ready = CompletableDeferred<Unit>()
        val engine = CallbackEngine()
        val coordinator = PlaybackCoordinator(engine, resolver { ready.await(); AppResult.Success(lease(it)) }, { network }, backgroundScope)
        val selection = launch { coordinator.select(a, entry(a), 0, true) }
        runCurrent()
        coordinator.pause()
        ready.complete(Unit)
        selection.join()
        assertEquals(a, engine.loadedTrack)
        assertFalse(engine.loadedPlayWhenReady)
        assertFalse(coordinator.snapshot.value.playWhenReady)
    }

    @Test fun seekClampsToActualDurationNotCatalogDuration() = runTest {
        val engine = CallbackEngine()
        val coordinator = PlaybackCoordinator(engine, resolver { AppResult.Success(lease(it)) }, { network }, backgroundScope)
        coordinator.select(a, entry(a), 0, false)
        engine.callback(PlayerStatus.PAUSED, duration = 10_000)
        runCurrent()
        coordinator.seek(20_000)
        assertEquals(10_000, engine.requestedSeek)
        coordinator.seek(-1)
        assertEquals(0, engine.requestedSeek)
    }

    @Test fun failedResolvePreservesSelectedMetadata() = runTest {
        val error = AppError(ErrorCode.SOURCE_UNAVAILABLE, "test.resolve", false)
        val coordinator = PlaybackCoordinator(CallbackEngine(), resolver { AppResult.Failure(error) }, { network }, backgroundScope)
        assertEquals(AppResult.Failure(error), coordinator.select(a, entry(a), 0, true))
        assertEquals(a, coordinator.snapshot.value.track)
        assertEquals(entry(a), coordinator.snapshot.value.current)
        assertEquals(PlayerStatus.FAILED, coordinator.snapshot.value.status)
        assertEquals(error, coordinator.snapshot.value.error)
    }

    @Test fun successfulLoadAndPlayCommandDoNotInventPlayingCallback() = runTest {
        val engine = CallbackEngine()
        val coordinator = PlaybackCoordinator(engine, resolver { AppResult.Success(lease(it)) }, { network }, backgroundScope)
        coordinator.select(a, entry(a), 0, true)
        coordinator.play()
        assertEquals(PlayerStatus.BUFFERING, coordinator.snapshot.value.status)
        engine.callback(PlayerStatus.PLAYING, position = 1234, duration = 10_000)
        runCurrent()
        assertEquals(PlayerStatus.PLAYING, coordinator.snapshot.value.status)
        assertEquals(1234, coordinator.snapshot.value.positionMs)
    }

    @Test fun initialRejectedResolveGetsOnlyOneFreshResolveAndExplicitSelectionRefills() = runTest {
        var calls = 0
        val coordinator = PlaybackCoordinator(CallbackEngine(), resolver {
            calls++; AppResult.Failure(AppError(ErrorCode.URL_REJECTED, "resolver.test", true))
        }, { network }, backgroundScope)
        coordinator.select(a, entry(a), 1234, true)
        assertEquals(2, calls)
        assertEquals(PlayerStatus.FAILED, coordinator.snapshot.value.status)
        assertEquals(1234L, coordinator.snapshot.value.positionMs)
        coordinator.select(a, entry(a), 1234, true)
        assertEquals(4, calls)
    }

    @Test fun engineRecoveryRetainsDurationAndLatestCommandsAndObserverSurvivesNewSelection() = runTest {
        var calls = 0
        val retry = CompletableDeferred<Unit>()
        val engine = CallbackEngine()
        val coordinator = PlaybackCoordinator(engine, resolver {
            if (++calls == 2) retry.await()
            AppResult.Success(lease(it))
        }, { network }, backgroundScope)
        coordinator.select(a, entry(a), 0, true)
        engine.callback(PlayerStatus.PLAYING, 3456, 10000); runCurrent()
        engine.callback(PlayerStatus.FAILED, 3456, 10000, ErrorCode.URL_REJECTED); runCurrent()
        assertEquals(2, calls)
        assertEquals(a, coordinator.snapshot.value.track)
        coordinator.seek(20000); coordinator.pause()
        retry.complete(Unit); runCurrent()
        assertEquals(10000L, engine.snapshot.value.positionMs)
        assertFalse(engine.loadedPlayWhenReady)
        engine.callback(PlayerStatus.PLAYING, 4000, 10000); runCurrent()
        engine.callback(PlayerStatus.FAILED, 4000, 10000, ErrorCode.INCOMPATIBLE_RESOURCE); runCurrent()
        assertEquals(2, calls)
        assertEquals(PlayerStatus.FAILED, coordinator.snapshot.value.status)
        coordinator.select(b, entry(b), 0, true)
        engine.callback(PlayerStatus.PLAYING, 2000, 10000); runCurrent()
        assertEquals(2000L, coordinator.snapshot.value.positionMs)
    }

    @Test fun reconnectCannotExceedTwoResolvesAndStaleEntryCannotConsumeNewAttemptsBudget() = runTest {
        var calls = 0
        val engine = CallbackEngine()
        val coordinator = PlaybackCoordinator(engine, resolver { calls++; AppResult.Success(lease(it)) }, { network }, backgroundScope)
        coordinator.select(a, entry(a), 1234, true)
        engine.callback(PlayerStatus.FAILED, 1234, error = ErrorCode.URL_REJECTED); runCurrent()
        assertEquals(2, calls)
        coordinator.networkInvalidated(); coordinator.pause(); coordinator.networkActivated(); runCurrent()
        assertEquals(2, calls)
        assertEquals(PlayerStatus.FAILED, coordinator.snapshot.value.status)
        assertTrue(coordinator.snapshot.value.error!!.retryable)
        assertEquals(a, coordinator.snapshot.value.track)
        assertEquals(1234L, coordinator.snapshot.value.positionMs)
        assertFalse(coordinator.snapshot.value.playWhenReady)
        coordinator.select(b, entry(b), 0, true)
        val current = engine.snapshot.value
        engine.snapshot.value = PlaybackSnapshot(PlayerStatus.FAILED, entry(a), a, error = AppError(ErrorCode.URL_REJECTED, "test", true))
        runCurrent(); assertEquals(3, calls)
        engine.snapshot.value = current.copy(status = PlayerStatus.FAILED, error = AppError(ErrorCode.URL_REJECTED, "test", true))
        runCurrent(); assertEquals(4, calls)
    }

    @Test fun networkRebindConsumesTheOneRemainingResolveWithoutRefundOnPlaying() = runTest {
        var calls = 0
        val engine = CallbackEngine()
        val coordinator = PlaybackCoordinator(engine, resolver { calls++; AppResult.Success(lease(it)) }, { network }, backgroundScope)
        coordinator.select(a, entry(a), 4321, true)
        coordinator.networkInvalidated(); coordinator.networkActivated(); runCurrent()
        assertEquals(2, calls)
        engine.callback(PlayerStatus.PLAYING, 4567, 10000); runCurrent()
        engine.callback(PlayerStatus.FAILED, 4567, 10000, ErrorCode.URL_REJECTED); runCurrent()
        assertEquals(2, calls)
        assertEquals(PlayerStatus.FAILED, coordinator.snapshot.value.status)
        coordinator.select(a, entry(a), 4567, false)
        assertEquals(3, calls)
        engine.callback(PlayerStatus.FAILED, 4567, error = ErrorCode.INCOMPATIBLE_RESOURCE); runCurrent()
        assertEquals(4, calls)
        assertFalse(engine.loadedPlayWhenReady)
    }

    @Test fun loadFailureAlsoUsesTheSharedFreshResolveBudget() = runTest {
        var calls = 0
        val engine = CallbackEngine().apply { loadError = ErrorCode.INCOMPATIBLE_RESOURCE }
        val coordinator = PlaybackCoordinator(engine, resolver { calls++; AppResult.Success(lease(it)) }, { network }, backgroundScope)
        coordinator.select(a, entry(a), 99, false)
        assertEquals(2, calls)
        assertEquals(PlayerStatus.FAILED, coordinator.snapshot.value.status)
        assertEquals(a, coordinator.snapshot.value.track)
    }

    @Test fun cancellingAutomaticRecoveryCannotKillEngineObserverAndDiagnosticsStayStructured() = runTest {
        var calls = 0
        val engine = CallbackEngine()
        val events = mutableListOf<app.musicplayer.diagnostics.DiagnosticEvent>()
        val coordinator = PlaybackCoordinator(engine, resolver {
            if (++calls == 2) awaitCancellation()
            AppResult.Success(lease(it))
        }, { network }, backgroundScope, events::add, "0.10.0", { testScheduler.currentTime })
        coordinator.select(a, entry(a), 0, true)
        engine.callback(PlayerStatus.FAILED, error = ErrorCode.URL_REJECTED); runCurrent()
        assertEquals(2, calls)
        coordinator.select(b, entry(b), 0, true); runCurrent()
        engine.callback(PlayerStatus.PLAYING, 2345, 10000); runCurrent()
        assertEquals(2345L, coordinator.snapshot.value.positionMs)
        assertTrue(events.any { it.stage == "engine" && it.errorCode == ErrorCode.URL_REJECTED })
        assertTrue(events.all { it.providerVersion == "0.10.0" && it.sourceId in listOf(a.candidates.first().id.value, b.candidates.first().id.value) })
        assertFalse(events.toString().contains("https://"))
    }

    @Test fun oldErrorFromSameOccurrenceCannotConsumeNewExplicitAttemptsBudget() = runTest {
        var calls = 0
        val engine = CallbackEngine()
        val coordinator = PlaybackCoordinator(engine, resolver { calls++; AppResult.Success(lease(it)) }, { network }, backgroundScope)
        coordinator.select(a, entry(a), 0, true); runCurrent()
        val old = engine.snapshot.value
        coordinator.select(a, entry(a), 1234, true); runCurrent()
        val current = engine.snapshot.value
        engine.snapshot.value = old.copy(status = PlayerStatus.FAILED, error = AppError(ErrorCode.URL_REJECTED, "old.load", true))
        runCurrent()
        assertEquals(2, calls, "Old same-entry load must not trigger recovery")
        assertEquals(1234L, coordinator.snapshot.value.positionMs)
        engine.snapshot.value = current.copy(status = PlayerStatus.FAILED, error = AppError(ErrorCode.URL_REJECTED, "current.load", true))
        runCurrent()
        assertEquals(3, calls)
        assertEquals(PlayerStatus.BUFFERING, coordinator.snapshot.value.status)
    }

    @Test fun untaggedCallbackCannotConsumeTaggedActiveLoadsBudget() = runTest {
        var calls = 0
        val engine = CallbackEngine()
        val coordinator = PlaybackCoordinator(engine, resolver { calls++; AppResult.Success(lease(it)) }, { network }, backgroundScope)
        coordinator.select(a, entry(a), 1234, true); runCurrent()
        val current = engine.snapshot.value
        engine.snapshot.value = current.copy(loadId = null, positionMs = 0, status = PlayerStatus.FAILED,
            error = AppError(ErrorCode.URL_REJECTED, "unidentified.load", true))
        runCurrent()
        assertEquals(1, calls)
        assertEquals(1234L, coordinator.snapshot.value.positionMs)
        engine.snapshot.value = current.copy(status = PlayerStatus.FAILED, error = AppError(ErrorCode.URL_REJECTED, "current.load", true))
        runCurrent(); assertEquals(2, calls)
    }

    private class CallbackEngine : PlayerEngine {
        override val snapshot = MutableStateFlow(PlaybackSnapshot())
        var loadError: ErrorCode? = null
        var loadedTrack: Track? = null
        var loadedPlayWhenReady = false
        var requestedSeek = -1L
        override suspend fun load(entry: QueueEntry, source: PlayableSource, positionMs: Long, playWhenReady: Boolean): AppResult<Unit> {
            loadError?.let { return AppResult.Failure(AppError(it, "player.load", true)) }
            loadedTrack = source.track
            loadedPlayWhenReady = playWhenReady
            snapshot.value = PlaybackSnapshot(PlayerStatus.BUFFERING, entry, source.track, positionMs, null, playWhenReady,
                loadId = (source as? PlayableSource.Remote)?.loadId)
            return AppResult.Success(Unit)
        }
        fun callback(status: PlayerStatus, position: Long = 0, duration: Long? = null, error: ErrorCode? = null) {
            snapshot.value = snapshot.value.copy(status = status, positionMs = position, durationMs = duration, error = error?.let { AppError(it, "player.callback", true) })
        }
        override fun play() {}
        override fun pause() {}
        override fun seek(positionMs: Long) { requestedSeek = positionMs }
        override fun stop() { snapshot.value = PlaybackSnapshot() }
        override fun release() {}
    }
}
