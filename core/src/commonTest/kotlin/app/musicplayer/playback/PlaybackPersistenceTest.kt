package app.musicplayer.playback

import app.musicplayer.diagnostics.DiagnosticEvent
import app.musicplayer.fixtures.Fixtures
import app.musicplayer.library.LibraryRepository
import app.musicplayer.model.*
import app.musicplayer.queue.QueueController
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackPersistenceTest {
    private val track = Fixtures.track("saved", sourceId = "saved")
    private val entry = QueueEntry(QueueEntryId("saved-occurrence"), track.id)
    private val saved = QueueSnapshot(listOf(entry), entry.id, 4321, true)
    private val network =
        NetworkContext(0, NetworkConfig(NetworkMode.SYSTEM, null, null), true, true)

    private class Engine : PlayerEngine {
        override val snapshot = MutableStateFlow(PlaybackSnapshot())

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
            snapshot.value =
                snapshot.value.copy(status = PlayerStatus.PAUSED, playWhenReady = false)
        }

        override fun seek(positionMs: Long) {
            snapshot.value = snapshot.value.copy(positionMs = positionMs)
        }

        override fun stop() {
            snapshot.value = PlaybackSnapshot()
        }

        override fun release() {}
    }

    private inner class Harness(val owner: CoroutineScope, val clock: () -> Long) {
        val engine = Engine()
        var calls = 0
        val repository = Repository()
        val events = mutableListOf<DiagnosticEvent>()
        val coordinator =
            PlaybackCoordinator(
                engine,
                object : PlaybackResolver {
                    override suspend fun resolve(
                        candidate: PlaybackCandidate,
                        capabilities: PlaybackCapabilities,
                        network: NetworkContext,
                    ): AppResult<ResolvedPlayback> {
                        calls++
                        return AppResult.Success(
                            ResolvedPlayback(
                                candidate.id,
                                Representation("wav", "audio/wav", "pcm", null, 10000, null),
                                TransportKind.PROGRESSIVE,
                                MediaRequest("https://example.invalid/audio", emptyMap()),
                                null,
                                0,
                            )
                        )
                    }
                },
                { network },
                owner,
            )
        var beforeLookup: suspend () -> Unit = {}
        val queue =
            QueueController(engine, coordinator) {
                beforeLookup()
                track
            }
        val persistence =
            PlaybackPersistence(
                repository,
                queue,
                coordinator,
                owner,
                clock,
                { 100000 + clock() },
                events::add,
            )

        init {
            owner.launch { coordinator.snapshot.collect { queue.onPlayback(it) } }
        }
    }

    @Test
    fun restoreIsPausedMetadataOnlyAndFirstWriteCannotEraseSavedQueue() = runTest {
        val h = Harness(backgroundScope) { testScheduler.currentTime }
        val gate = CompletableDeferred<Unit>()
        h.repository.beforeRestore = { gate.await() }
        val init = launch { h.persistence.initialize() }
        runCurrent()
        assertTrue(h.repository.saves.isEmpty())
        gate.complete(Unit)
        init.join()
        runCurrent()
        assertEquals(
            app.musicplayer.queue.QueueOrder.normalized(
                saved.copy(playWhenReady = false, revision = 1)
            ),
            h.queue.state.value,
        )
        assertEquals(track, h.queue.snapshot.value.track)
        assertEquals(PlayerStatus.PAUSED, h.queue.snapshot.value.status)
        assertEquals(0, h.calls)
        assertEquals(
            listOf(
                app.musicplayer.queue.QueueOrder.normalized(
                    saved.copy(playWhenReady = false, revision = 1)
                )
            ),
            h.repository.saves,
        )
        assertEquals(1, h.repository.prunes)
        h.coordinator.networkInvalidated()
        h.coordinator.networkActivated()
        runCurrent()
        assertEquals(0, h.calls, "Network activation must not resolve restored metadata")
        h.queue.play()
        runCurrent()
        assertEquals(1, h.calls)
        assertEquals(4321L, h.engine.snapshot.value.positionMs)
        assertTrue(h.engine.snapshot.value.playWhenReady)
    }

    @Test
    fun historyWaitsForRealPlayingAndRecordsOnceAcrossPauseRecoveryAndReconnect() = runTest {
        val h = Harness(backgroundScope) { testScheduler.currentTime }
        h.persistence.initialize()
        h.queue.play()
        runCurrent()
        assertTrue(h.repository.starts.isEmpty())
        h.engine.snapshot.value = h.engine.snapshot.value.copy(status = PlayerStatus.PLAYING)
        runCurrent()
        assertEquals(listOf(track.id), h.repository.starts)
        h.queue.pause()
        runCurrent()
        h.queue.play()
        runCurrent()
        h.engine.snapshot.value = h.engine.snapshot.value.copy(status = PlayerStatus.PLAYING)
        runCurrent()
        h.coordinator.networkInvalidated()
        h.coordinator.networkActivated()
        runCurrent()
        h.engine.snapshot.value = h.engine.snapshot.value.copy(status = PlayerStatus.PLAYING)
        runCurrent()
        assertEquals(listOf(track.id), h.repository.starts)
        h.queue.select(entry, 0, true)
        runCurrent()
        h.engine.snapshot.value = h.engine.snapshot.value.copy(status = PlayerStatus.PLAYING)
        runCurrent()
        assertEquals(listOf(track.id, track.id), h.repository.starts)
    }

    @Test
    fun periodicActualProgressPauseAndStopCheckpointKeepLatestPosition() = runTest {
        val h = Harness(backgroundScope) { testScheduler.currentTime }
        h.persistence.initialize()
        h.queue.play()
        runCurrent()
        h.engine.snapshot.value = h.engine.snapshot.value.copy(status = PlayerStatus.PLAYING)
        runCurrent()
        val initial = h.repository.saves.size
        h.engine.snapshot.value = h.engine.snapshot.value.copy(positionMs = 7654)
        runCurrent()
        advanceTimeBy(4999)
        runCurrent()
        assertEquals(initial, h.repository.saves.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(7654L, h.repository.saves.last().positionMs)
        h.engine.snapshot.value = h.engine.snapshot.value.copy(positionMs = 8000)
        runCurrent()
        h.queue.pause()
        runCurrent()
        assertEquals(8000L, h.repository.saves.last().positionMs)
        assertFalse(h.repository.saves.last().playWhenReady)
        h.queue.seek(9000)
        runCurrent()
        h.persistence.stop()
        runCurrent()
        assertEquals(9000L, h.repository.saves.last().positionMs)
        assertNull(h.engine.snapshot.value.current)
        assertEquals(entry.id, h.repository.saves.last().currentEntryId)
    }

    @Test
    fun saveFailureIsDatabaseStateAndSafeDiagnosticWithoutKillingPersistence() = runTest {
        val h = Harness(backgroundScope) { testScheduler.currentTime }
        h.repository.failSave = true
        h.persistence.initialize()
        runCurrent()
        assertEquals(ErrorCode.DATABASE, h.persistence.error.value?.code)
        assertTrue(h.events.any { it.stage == "save" && it.errorCode == ErrorCode.DATABASE })
        assertFalse(h.events.toString().contains("test-password"))
        h.repository.failSave = false
        h.queue.seek(4567)
        runCurrent()
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(4567L, h.repository.saves.last().positionMs)
    }

    @Test
    fun restorationFailureNeverOverwritesStorageAndCancellationPropagates() = runTest {
        val h = Harness(backgroundScope) { testScheduler.currentTime }
        h.repository.beforeRestore = { throw IllegalStateException("test-password") }
        h.persistence.initialize()
        runCurrent()
        assertEquals(ErrorCode.DATABASE, h.persistence.error.value?.code)
        assertTrue(h.repository.saves.isEmpty())
        val other = Harness(backgroundScope) { testScheduler.currentTime }
        other.repository.beforeRestore = { throw CancellationException("shutdown") }
        assertFailsWith<CancellationException> { other.persistence.initialize() }
    }

    @Test
    fun editingRestoredQueueKeepsPositionUntilPlayAndCloseFlushesActualProgress() = runTest {
        val h = Harness(backgroundScope) { testScheduler.currentTime }
        h.persistence.initialize()
        runCurrent()
        val extra = QueueEntry(QueueEntryId("extra"), track.id)
        h.queue.dispatch(app.musicplayer.queue.QueueCommand.Add(listOf(extra)))
        h.queue.play()
        runCurrent()
        assertEquals(4321L, h.engine.snapshot.value.positionMs)
        h.engine.snapshot.value =
            h.engine.snapshot.value.copy(status = PlayerStatus.PLAYING, positionMs = 9876)
        runCurrent()
        h.persistence.close()
        assertEquals(9876L, h.repository.saves.last().positionMs)
        assertFalse(h.repository.saves.last().playWhenReady)
    }

    @Test
    fun metadataFailureIsNormalizedAndCancellationFromSavingStillPropagates() = runTest {
        val h = Harness(backgroundScope) { testScheduler.currentTime }
        h.repository.beforeUpsert = { error("test-password") }
        assertEquals(ErrorCode.DATABASE, h.persistence.remember(track).errorOrNull()?.code)
        h.repository.beforeUpsert = { throw CancellationException("shutdown") }
        assertFailsWith<CancellationException> { h.persistence.remember(track) }
        h.persistence.initialize()
        runCurrent()
        h.repository.beforeSave = { throw CancellationException("shutdown") }
        assertFailsWith<CancellationException> { h.persistence.checkpoint() }
    }

    @Test
    fun slowStopCheckpointCannotClearASelectionIssuedAfterStop() = runTest {
        val h = Harness(backgroundScope) { testScheduler.currentTime }
        h.persistence.initialize()
        h.queue.play()
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        h.repository.beforeSave = { gate.await() }
        val stopping = launch { h.persistence.stop() }
        runCurrent()
        val next = QueueEntry(QueueEntryId("new-selection"), track.id)
        val selecting = launch { h.queue.select(next, 123, true) }
        runCurrent()
        gate.complete(Unit)
        stopping.join()
        selecting.join()
        runCurrent()
        assertEquals(next, h.engine.snapshot.value.current)
        assertEquals(next.id, h.queue.state.value.currentEntryId)
        assertTrue(h.engine.snapshot.value.playWhenReady)
    }

    @Test
    fun queuedOldCallbackCannotReplaceRestoredPausedMetadata() = runTest {
        val h = Harness(backgroundScope) { testScheduler.currentTime }
        h.persistence.initialize()
        h.queue.play()
        runCurrent()
        val old = h.engine.snapshot.value.copy(status = PlayerStatus.PLAYING, positionMs = 7890)
        val gate = CompletableDeferred<Unit>()
        h.beforeLookup = { gate.await() }
        val restoring = launch { h.queue.restore(saved) }
        runCurrent()
        val callback = launch { h.queue.onPlayback(old) }
        runCurrent()
        gate.complete(Unit)
        restoring.join()
        callback.join()
        runCurrent()
        assertEquals(PlayerStatus.PAUSED, h.queue.snapshot.value.status)
        assertEquals(4321L, h.queue.state.value.positionMs)
        assertFalse(h.queue.state.value.playWhenReady)
    }

    @Test
    fun failedPausedWriteRetriesAt5000WithoutNewCommandsAndUnrelatedSuccessCannotClearIt() =
        runTest {
            val h = Harness(backgroundScope) { testScheduler.currentTime }
            h.persistence.initialize()
            runCurrent()
            h.repository.failSave = true
            h.queue.seek(7890)
            runCurrent()
            val attempted = h.repository.saveAttempts
            assertEquals(4321L, h.repository.saves.last().positionMs)
            assertEquals(ErrorCode.DATABASE, h.persistence.error.value?.code)
            h.repository.failSave = false
            assertTrue(h.persistence.remember(track) is AppResult.Success)
            assertEquals(ErrorCode.DATABASE, h.persistence.error.value?.code)
            advanceTimeBy(4999)
            runCurrent()
            assertEquals(attempted, h.repository.saveAttempts)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(7890L, h.repository.saves.last().positionMs)
            assertFalse(h.repository.saves.last().playWhenReady)
            assertNull(h.persistence.error.value)
        }

    @Test
    fun restoreFailureIsPublishedEvenWithEmptyQueueWithoutAdvertisingPlaybackRetry() = runTest {
        val h = Harness(backgroundScope) { testScheduler.currentTime }
        h.repository.beforeRestore = { error("test-password") }
        h.persistence.initialize()
        runCurrent()
        val published = h.persistence.snapshot.value
        assertNull(published.current)
        assertEquals(PlayerStatus.IDLE, published.status)
        assertEquals(ErrorCode.DATABASE, published.error?.code)
        assertFalse(published.error!!.retryable)
        assertTrue(h.repository.saves.isEmpty())
    }

    @Test
    fun publishedStorageFailurePreservesPlaybackAndActualPlaybackErrorTakesPrecedence() = runTest {
        val h = Harness(backgroundScope) { testScheduler.currentTime }
        h.persistence.initialize()
        h.queue.play()
        runCurrent()
        h.engine.snapshot.value =
            h.engine.snapshot.value.copy(status = PlayerStatus.PLAYING, positionMs = 7654)
        runCurrent()
        h.repository.failSave = true
        h.persistence.checkpoint()
        runCurrent()
        val published = h.persistence.snapshot.value
        assertEquals(PlayerStatus.PLAYING, published.status)
        assertEquals(track, published.track)
        assertEquals(7654L, published.positionMs)
        assertTrue(published.playWhenReady)
        assertEquals(ErrorCode.DATABASE, published.error?.code)
        assertFalse(published.error!!.retryable)
        h.engine.snapshot.value =
            h.engine.snapshot.value.copy(
                status = PlayerStatus.FAILED,
                error = AppError(ErrorCode.NETWORK, "player.callback", true),
            )
        runCurrent()
        assertEquals(ErrorCode.NETWORK, h.persistence.snapshot.value.error?.code)
        h.repository.failSave = false
        advanceTimeBy(5000)
        runCurrent()
        assertNull(h.persistence.error.value)
        assertEquals(ErrorCode.NETWORK, h.persistence.snapshot.value.error?.code)
    }

    private inner class Repository : LibraryRepository {
        val saves = mutableListOf<QueueSnapshot>()
        val starts = mutableListOf<TrackId>()
        var beforeUpsert: suspend () -> Unit = {}
        var beforeSave: suspend () -> Unit = {}
        var saveAttempts = 0
        var failSave = false
        var prunes = 0
        var beforeRestore: suspend () -> Unit = {}

        override suspend fun restoreQueue(): QueueSnapshot {
            beforeRestore()
            return saved
        }

        override suspend fun saveQueue(snapshot: QueueSnapshot) {
            saveAttempts++
            beforeSave()
            if (failSave) error("test-password")
            saves += snapshot
        }

        override suspend fun recordStarted(id: TrackId, atMs: Long) {
            starts += id
        }

        override suspend fun prune(nowMs: Long) {
            prunes++
        }

        override suspend fun getTrack(id: TrackId) = track

        override suspend fun upsert(track: Track) {
            beforeUpsert()
        }

        override suspend fun getOrCreate(source: SourceRef) = track.id

        override suspend fun setFavorite(id: TrackId, favorite: Boolean) {}

        override suspend fun getCachedSearch(
            query: String,
            musicOnly: Boolean,
            nowMs: Long,
        ): List<Track>? = null

        override suspend fun cacheSearch(
            query: String,
            musicOnly: Boolean,
            tracks: List<Track>,
            atMs: Long,
        ) {}

        override suspend fun getNetworkConfig() = network.config

        override suspend fun saveNetworkConfig(config: NetworkConfig) {}

        override suspend fun getLyrics(
            id: TrackId,
            candidateId: CandidateId,
            nowMs: Long,
        ): LyricsLookup? = null

        override suspend fun saveLyrics(
            id: TrackId,
            candidateId: CandidateId,
            lookup: LyricsLookup,
            atMs: Long,
        ) {}

        override val favorites: Flow<List<Track>> = emptyFlow()
        override val recent: Flow<List<Track>> = emptyFlow()
    }
}
