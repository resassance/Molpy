package app.musicplayer.queue

import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class QueuePlaylistAdmissionTest {
    @Test
    fun newerManualAddPreventsLateSourceInstallation() = runTest {
        val h = QueuePlaylistTestFixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        h.beforeRememberOne = {
            if (it.id.value == "a") withContext(NonCancellable) { gate.await() }
        }
        val old = launch { h.loader.play(h.request(next = null)) }
        runCurrent()
        h.commands.enqueue(h.track("n1"))
        gate.complete(Unit)
        old.join()
        runCurrent()
        assertEquals(listOf("n1"), h.playback.queue.state.value.entries.map { it.trackId.value })
        assertFalse(h.playback.queue.state.value.playWhenReady)
        assertEquals(0, h.playback.resolves)
        assertNull(h.playback.queue.state.value.source)
    }

    @Test
    fun secondSourceWinsDespiteFirstMetadataFinishingLast() = runTest {
        val h = QueuePlaylistTestFixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        h.beforeRememberOne = {
            if (it.id.value == "a") withContext(NonCancellable) { gate.await() }
        }
        val old = launch { h.loader.play(h.request(next = null)) }
        runCurrent()
        h.loader.play(h.request(listOf("b"), next = null))
        runCurrent()
        gate.complete(Unit)
        old.join()
        runCurrent()
        assertEquals("b", h.playback.engine.snapshot.value.track!!.id.value)
        assertEquals(listOf("b"), h.playback.queue.state.value.entries.map { it.trackId.value })
        assertEquals(QueueSourcePhase.COMPLETE, h.playback.queue.state.value.source!!.phase)
    }

    @Test
    fun stopRejectsLateSelectedMetadata() = runTest {
        val h = QueuePlaylistTestFixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        h.beforeRememberOne = {
            if (it.id.value == "a") withContext(NonCancellable) { gate.await() }
        }
        val old = launch { h.loader.play(h.request(next = null)) }
        runCurrent()
        h.loader.stop()
        h.commands.invalidateQueue()
        gate.complete(Unit)
        old.join()
        runCurrent()
        assertTrue(h.playback.queue.state.value.entries.isEmpty())
        assertEquals(0, h.playback.resolves)
    }
}
