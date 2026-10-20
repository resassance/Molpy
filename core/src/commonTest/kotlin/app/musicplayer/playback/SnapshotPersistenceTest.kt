package app.musicplayer.playback

import app.musicplayer.library.LibraryRepository
import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotPersistenceTest {
    private val entry = QueueEntry(QueueEntryId("entry"), TrackId("track"))
    private fun snapshot(position: Long = 0, play: Boolean = true) = QueueSnapshot(listOf(entry), entry.id, position, play)

    @Test fun savesInitialSnapshotThenLatestActualProgressAt5000Not4999() = runTest {
        val repository = QueueRepository()
        val queue = MutableStateFlow(snapshot(12_000))
        val persistence = SnapshotPersistence(repository, { testScheduler.currentTime }, { true })
        backgroundScope.launch { persistence.start(queue) }
        runCurrent()
        assertEquals(listOf(snapshot(12_000)), repository.saves)
        queue.value = snapshot(15_000)
        runCurrent()
        advanceTimeBy(4999)
        queue.value = snapshot(16_999)
        runCurrent()
        assertEquals(1, repository.saves.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(snapshot(12_000), snapshot(16_999)), repository.saves)
    }

    @Test fun intentWithoutActualPlaybackDoesNotPeriodicallySaveProgress() = runTest {
        val repository = QueueRepository()
        var actualPlaying = false
        val queue = MutableStateFlow(snapshot())
        val persistence = SnapshotPersistence(repository, { testScheduler.currentTime }, { actualPlaying })
        backgroundScope.launch { persistence.start(queue) }
        runCurrent()
        queue.value = snapshot(100)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(snapshot()), repository.saves)
        actualPlaying = true
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(snapshot(100), repository.saves.last())
    }

    @Test fun pauseSeekTrackAndQueueTransitionsSaveImmediatelyWithoutPeriodicPausedWrites() = runTest {
        val repository = QueueRepository()
        val queue = MutableStateFlow(snapshot(1000))
        var playing = true
        val persistence = SnapshotPersistence(repository, { testScheduler.currentTime }, { playing })
        backgroundScope.launch { persistence.start(queue) }
        runCurrent()
        advanceTimeBy(100)
        playing = false
        queue.value = snapshot(1100, false)
        runCurrent()
        assertEquals(queue.value, repository.saves.last())
        queue.value = snapshot(9000, false)
        runCurrent()
        assertEquals(queue.value, repository.saves.last())
        val other = QueueEntry(QueueEntryId("other"), TrackId("other-track"))
        queue.value = QueueSnapshot(listOf(entry, other), other.id, 3200, false)
        runCurrent()
        assertEquals(queue.value, repository.saves.last())
        queue.value = queue.value.copy(entries = listOf(other))
        runCurrent()
        assertEquals(queue.value, repository.saves.last())
        val savedCount = repository.saves.size
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(savedCount, repository.saves.size)
    }

    @Test fun initialPausedStateIsSavedAndExplicitFlushWinsOverOlderPendingProgress() = runTest {
        val repository = QueueRepository()
        val queue = MutableStateFlow(snapshot(40_000, false))
        val persistence = SnapshotPersistence(repository, { testScheduler.currentTime }, { true })
        backgroundScope.launch { persistence.start(queue) }
        runCurrent()
        assertEquals(listOf(snapshot(40_000, false)), repository.saves)
        queue.value = snapshot(41_000)
        runCurrent()
        queue.value = snapshot(44_999)
        runCurrent()
        val latest = snapshot(45_000, false)
        persistence.flush(latest)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(latest, repository.saves.last())
        assertEquals(1, repository.saves.count { it == latest })
    }

    @Test fun flushSerializesWithInFlightSaveAndRetainsLatestPositionAndIntent() = runTest {
        val repository = QueueRepository()
        val queue = MutableStateFlow(snapshot())
        val persistence = SnapshotPersistence(repository, { testScheduler.currentTime }, { true })
        backgroundScope.launch { persistence.start(queue) }
        runCurrent()
        val blocked = CompletableDeferred<Unit>()
        repository.beforeSave = { blocked.await() }
        queue.value = snapshot(5000)
        advanceTimeBy(5000)
        runCurrent()
        val latest = snapshot(5200, false)
        val flushing = launch { persistence.flush(latest) }
        runCurrent()
        blocked.complete(Unit)
        flushing.join()
        assertEquals(latest, repository.saves.last())
        assertEquals(1, repository.maximumConcurrentSaves)
    }

    private class QueueRepository : LibraryRepository {
        val saves = mutableListOf<QueueSnapshot>()
        var beforeSave: suspend () -> Unit = {}
        private var concurrentSaves = 0
        var maximumConcurrentSaves = 0
        override suspend fun saveQueue(snapshot: QueueSnapshot) {
            concurrentSaves++
            maximumConcurrentSaves = maxOf(maximumConcurrentSaves, concurrentSaves)
            try { beforeSave(); saves += snapshot } finally { concurrentSaves-- }
        }
        override suspend fun restoreQueue() = saves.lastOrNull() ?: QueueSnapshot()
        override suspend fun getOrCreate(source: SourceRef): TrackId = error("Unused")
        override suspend fun upsert(track: Track): Unit = error("Unused")
        override suspend fun getTrack(id: TrackId): Track? = error("Unused")
        override suspend fun setFavorite(id: TrackId, favorite: Boolean): Unit = error("Unused")
        override suspend fun recordStarted(id: TrackId, atMs: Long): Unit = error("Unused")
        override suspend fun getCachedSearch(query: String, musicOnly: Boolean, nowMs: Long): List<Track>? = error("Unused")
        override suspend fun cacheSearch(query: String, musicOnly: Boolean, tracks: List<Track>, atMs: Long): Unit = error("Unused")
        override suspend fun prune(nowMs: Long): Unit = error("Unused")
        override suspend fun getNetworkConfig(): NetworkConfig = error("Unused")
        override suspend fun saveNetworkConfig(config: NetworkConfig): Unit = error("Unused")
        override suspend fun getLyrics(id: TrackId, candidateId: CandidateId, nowMs: Long): LyricsLookup? = error("Unused")
        override suspend fun saveLyrics(id: TrackId, candidateId: CandidateId, lookup: LyricsLookup, atMs: Long): Unit = error("Unused")
        override val favorites: Flow<List<Track>> = emptyFlow()
        override val recent: Flow<List<Track>> = emptyFlow()
    }
}
