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

    private class CallbackEngine : PlayerEngine {
        override val snapshot = MutableStateFlow(PlaybackSnapshot())
        var loadedTrack: Track? = null
        var loadedPlayWhenReady = false
        var requestedSeek = -1L
        override suspend fun load(entry: QueueEntry, source: PlayableSource, positionMs: Long, playWhenReady: Boolean): AppResult<Unit> {
            loadedTrack = source.track
            loadedPlayWhenReady = playWhenReady
            snapshot.value = PlaybackSnapshot(PlayerStatus.BUFFERING, entry, source.track, positionMs, null, playWhenReady)
            return AppResult.Success(Unit)
        }
        fun callback(status: PlayerStatus, position: Long = 0, duration: Long? = null) {
            snapshot.value = snapshot.value.copy(status = status, positionMs = position, durationMs = duration)
        }
        override fun play() {}
        override fun pause() {}
        override fun seek(positionMs: Long) { requestedSeek = positionMs }
        override fun stop() { snapshot.value = PlaybackSnapshot() }
        override fun release() {}
    }
}
