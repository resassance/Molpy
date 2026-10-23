package app.musicplayer.queue

import app.musicplayer.catalog.*
import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class QueuePlaylistLoaderTest {
    @Test
    fun completedPageSaveRetryAfterRuntimeResetDoesNotNeedNetwork() = runTest {
        val h = QueuePlaylistTestFixture(backgroundScope)
        h.failCommit = true
        h.loader.play(h.request(listOf("a", "b"), next = null))
        runCurrent()
        assertEquals(QueueSourcePhase.COMPLETE, h.playback.queue.state.value.source!!.phase)
        val ids = h.playback.queue.state.value.entries.map { it.id }
        h.loader.stop()
        h.failCommit = false
        assertIs<AppResult.Success<Unit>>(h.loader.retry())
        assertEquals(0, h.catalog.headCalls)
        assertEquals(ids, h.playback.queue.state.value.entries.map { it.id })
        assertEquals(QueueSourcePhase.COMPLETE, h.durable.source!!.phase)
        assertNull(h.durable.source!!.error)
    }

    @Test
    fun selectedTrackResolvesBeforeBlockedRemainingPage() = runTest {
        val h = QueuePlaylistTestFixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        h.catalog.read = {
            gate.await()
            AppResult.Success(h.page(1, listOf("b", "c")))
        }
        assertIs<AppResult.Success<Unit>>(h.loader.play(h.request()))
        runCurrent()
        assertEquals("a", h.playback.engine.snapshot.value.track!!.id.value)
        assertEquals(1, h.playback.resolves)
        assertTrue(h.future().isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("b", "c"), h.future())
        assertEquals(QueueSourcePhase.COMPLETE, h.playback.queue.state.value.source!!.phase)
    }

    @Test
    fun selectedSeedTrackDoesNotWaitForSavingTheRestOfItsPage() = runTest {
        val h = QueuePlaylistTestFixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        h.beforeRemember = { if (it.any { track -> track.id.value == "c" }) gate.await() }
        val playing: Deferred<AppResult<Unit>> = async {
            h.loader.play(h.request(listOf("a", "b", "c"), next = null, selected = 1))
        }
        runCurrent()
        assertEquals("b", h.playback.engine.snapshot.value.track!!.id.value)
        assertTrue(playing.isCompleted)
        assertEquals(listOf("b"), h.playback.queue.state.value.entries.map { it.trackId.value })
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("c"), h.future())
        assertTrue(h.playback.queue.state.value.traversal!!.history.isEmpty())
    }

    @Test
    fun closingScreenDoesNotCancelLoader() = runTest {
        val h = QueuePlaylistTestFixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        h.catalog.read = {
            gate.await()
            AppResult.Success(h.page(1, listOf("b")))
        }
        val screen = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        screen.launch { h.loader.play(h.request()) }
        runCurrent()
        screen.cancel()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("b"), h.future())
        assertEquals(QueueSourcePhase.COMPLETE, h.playback.queue.state.value.source!!.phase)
    }

    @Test
    fun replacementRejectsLatePage() = runTest {
        val h = QueuePlaylistTestFixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        h.catalog.read = {
            withContext(NonCancellable) { gate.await() }
            AppResult.Success(h.page(1, listOf("c")))
        }
        h.loader.play(h.request())
        runCurrent()
        h.commands.playList(listOf(h.track("b")), 0)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("b"), h.playback.queue.state.value.entries.map { it.trackId.value })
        assertNull(h.playback.queue.state.value.source)
        assertEquals("b", h.playback.engine.snapshot.value.track!!.id.value)
    }

    @Test
    fun nextInsertionDoesNotCancelSourceSessionAndEndRemainsAfterFuturePages() = runTest {
        val h = QueuePlaylistTestFixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        h.catalog.read = {
            gate.await()
            AppResult.Success(h.page(1, listOf("b", "c")))
        }
        h.loader.play(h.request())
        runCurrent()
        h.commands.enqueue(h.track("tail"), QueueInsertion.END)
        h.commands.enqueue(h.track("n1"), QueueInsertion.NEXT)
        h.commands.enqueue(h.track("n2"), QueueInsertion.NEXT)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("n2", "n1", "b", "c", "tail"), h.future())
        assertEquals(1, h.playback.resolves)
    }

    @Test
    fun retryUsesSamePendingPageWithoutNewEntries() = runTest {
        val h = QueuePlaylistTestFixture(backgroundScope)
        h.catalog.read = { AppResult.Success(h.page(1, listOf("b", "b"))) }
        h.failCommit = true
        h.loader.play(h.request())
        runCurrent()
        val entries = h.playback.queue.state.value.entries.map { it.id }
        assertEquals(1, h.catalog.calls)
        assertEquals(ErrorCode.DATABASE, h.playback.queue.state.value.source!!.error!!.code)
        assertEquals(QueueSourcePhase.COMPLETE, h.playback.queue.state.value.source!!.phase)
        h.failCommit = false
        assertIs<AppResult.Success<Unit>>(h.loader.retry())
        runCurrent()
        assertEquals(entries, h.playback.queue.state.value.entries.map { it.id })
        assertEquals(listOf("b", "b"), h.future())
        assertEquals(1, h.catalog.calls)
        assertNull(h.playback.queue.state.value.source!!.error)
    }

    @Test
    fun rateLimitHonorsDelayWithoutBlockingPause() = runTest {
        val h = QueuePlaylistTestFixture(backgroundScope)
        h.catalog.read = {
            if (h.catalog.calls == 1)
                AppResult.Failure(
                    AppError(ErrorCode.RATE_LIMIT, "catalog.rate", true, retryAfterMs = 5000)
                )
            else AppResult.Success(h.page(1, listOf("b")))
        }
        h.loader.play(h.request())
        runCurrent()
        h.playback.queue.pause()
        assertFalse(h.playback.queue.state.value.playWhenReady)
        advanceTimeBy(4999)
        runCurrent()
        assertEquals(1, h.catalog.calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, h.catalog.calls)
        assertEquals(listOf("b"), h.future())
        assertFalse(h.playback.queue.state.value.playWhenReady)
    }

    @Test
    fun rateLimitWithoutHintDoesNotCauseOuterRetryLoop() = runTest {
        val h = QueuePlaylistTestFixture(backgroundScope)
        h.catalog.read = { AppResult.Failure(AppError(ErrorCode.RATE_LIMIT, "catalog.rate", true)) }
        h.loader.play(h.request())
        runCurrent()
        advanceTimeBy(10000)
        runCurrent()
        assertEquals(1, h.catalog.calls)
        assertEquals(ErrorCode.RATE_LIMIT, h.playback.queue.state.value.source!!.error!!.code)
    }

    @Test
    fun nextWhilePageAutoStartsDoesNotCancelFeeder() = runTest {
        val h = QueuePlaylistTestFixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        h.catalog.read = { cursor ->
            if (cursor.value == "tail") {
                gate.await()
                AppResult.Success(h.page(1, listOf("b", "c"), "last"))
            } else AppResult.Success(h.page(3, listOf("d")))
        }
        h.playback.beforeResolve = { if (it.source.id == "b") awaitCancellation() }
        h.loader.play(h.request())
        runCurrent()
        h.playback.queue.dispatch(QueueCommand.Next)
        gate.complete(Unit)
        runCurrent()
        assertEquals(
            "b",
            h.playback.queue.state.value.currentEntryId!!.let { id ->
                h.playback.queue.state.value.entries.first { it.id == id }.trackId.value
            },
        )
        h.commands.dispatch(QueueCommand.Next)
        runCurrent()
        assertEquals("c", h.playback.engine.snapshot.value.track!!.id.value)
        assertEquals(listOf("d"), h.future())
        assertEquals(2, h.catalog.calls)
        assertEquals(QueueSourcePhase.COMPLETE, h.playback.queue.state.value.source!!.phase)
    }
}
