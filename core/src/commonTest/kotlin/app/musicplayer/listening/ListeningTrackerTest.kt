package app.musicplayer.listening

import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ListeningTrackerTest {
    @Test
    fun failedWritesCoalesceEverySessionAndRetryWithoutBlockingSnapshots() = runTest {
        val repo = MemoryListening()
        repo.fail = true
        val snapshots = MutableStateFlow(listeningSnapshot())
        val tracker =
            ListeningTracker(
                repo,
                snapshots,
                backgroundScope,
                { testScheduler.currentTime },
                { 100000 + testScheduler.currentTime },
            )
        runCurrent()
        assertEquals(ErrorCode.DATABASE, tracker.error.value?.code)
        advanceTimeBy(1000)
        snapshots.value = listeningSnapshot(status = PlayerStatus.PAUSED)
        runCurrent()
        snapshots.value = listeningSnapshot(attempt = 2, id = "b")
        runCurrent()
        advanceTimeBy(1000)
        snapshots.value = listeningSnapshot(attempt = 2, id = "b", status = PlayerStatus.PAUSED)
        runCurrent()
        assertEquals(1, repo.calls)
        repo.fail = false
        advanceTimeBy(3000)
        runCurrent()
        assertEquals(2, repo.saved.size)
        assertEquals(1000L, repo.saved.values.first { it.track.id.value == "a" }.playedMs)
        assertEquals(1000L, repo.saved.values.first { it.track.id.value == "b" }.playedMs)
        assertNull(tracker.error.value)
        tracker.close()
    }

    @Test
    fun ownerStopCapturesFinalIntervalBeforeParentCancellation() = runTest {
        val repo = MemoryListening()
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val snapshots = MutableStateFlow(listeningSnapshot())
        val tracker =
            ListeningTracker(
                repo,
                snapshots,
                owner,
                { testScheduler.currentTime },
                { 100000 + testScheduler.currentTime },
            )
        runCurrent()
        advanceTimeBy(1234)
        tracker.ownerStopped()
        owner.cancel()
        tracker.close()
        assertEquals(1234L, repo.saved.values.single().playedMs)
        advanceTimeBy(20000)
        runCurrent()
        assertEquals(1234L, repo.saved.values.single().playedMs)
    }

    @Test
    fun stalledWriterDoesNotBlockSwitchAndCloseHasBoundedFlush() = runTest {
        val repo = MemoryListening().apply { blocked = CompletableDeferred() }
        val snapshots = MutableStateFlow(listeningSnapshot())
        val tracker =
            ListeningTracker(
                repo,
                snapshots,
                backgroundScope,
                { testScheduler.currentTime },
                { 100000 + testScheduler.currentTime },
            )
        runCurrent()
        advanceTimeBy(1000)
        snapshots.value = listeningSnapshot(attempt = 2, id = "b")
        runCurrent()
        tracker.ownerStopped()
        val close = async { tracker.close() }
        advanceTimeBy(1001)
        runCurrent()
        assertTrue(close.isCompleted)
        assertTrue(snapshots.value.track?.id?.value == "b")
    }

    private class MemoryListening : ListeningRepository {
        var fail = false
        var blocked: CompletableDeferred<Unit>? = null
        var calls = 0
        val saved = linkedMapOf<String, ListeningCheckpoint>()
        override val profile: Flow<AppResult<ListeningProfile>> =
            flowOf(AppResult.Success(ListeningProfile()))

        override suspend fun save(checkpoint: ListeningCheckpoint): AppResult<Unit> {
            calls++
            blocked?.await()
            if (fail) return AppResult.Failure(AppError(ErrorCode.DATABASE, "fixture", true))
            if (checkpoint.revision >= (saved[checkpoint.sessionId]?.revision ?: 0))
                saved[checkpoint.sessionId] = checkpoint
            return AppResult.Success(Unit)
        }

        override suspend fun history(cursor: HistoryCursor?, limit: Int) =
            AppResult.Success(HistoryPage(emptyList(), null))
    }
}
