package app.musicplayer.queue

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class QueueInsertionGateTest {
    @Test
    fun acceptedInsertionsWaitInOrderWithoutCancellingEachOther() = runTest {
        val gate = QueueInsertionGate()
        val waiting = CompletableDeferred<Unit>()
        val accepted = mutableListOf<Int>()
        val first = launch {
            gate.run { check ->
                waiting.await()
                check()
                accepted.add(1)
            }
        }
        runCurrent()
        val second = launch {
            gate.run { check ->
                check()
                accepted.add(2)
            }
        }
        runCurrent()
        assertTrue(accepted.isEmpty())
        waiting.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf(1, 2), accepted)
    }

    @Test
    fun invalidationIncludesCommandsStillWaitingForTheLock() = runTest {
        val gate = QueueInsertionGate()
        val waiting = CompletableDeferred<Unit>()
        val accepted = mutableListOf<Int>()
        val first = launch {
            gate.run { check ->
                withContext(NonCancellable) { waiting.await() }
                check()
                accepted.add(1)
            }
        }
        runCurrent()
        val second = launch {
            gate.run { check ->
                check()
                accepted.add(2)
            }
        }
        runCurrent()
        gate.invalidate()
        waiting.complete(Unit)
        first.join()
        second.join()
        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
        assertTrue(accepted.isEmpty())
        gate.run { check ->
            check()
            accepted.add(3)
        }
        assertEquals(listOf(3), accepted)
    }

    @Test
    fun callerCancellationAfterNonCancellableIoCannotCommit() = runTest {
        val gate = QueueInsertionGate()
        val waiting = CompletableDeferred<Unit>()
        var committed = false
        val pending = launch {
            gate.run { check ->
                withContext(NonCancellable) { waiting.await() }
                check()
                committed = true
            }
        }
        runCurrent()
        pending.cancel()
        waiting.complete(Unit)
        pending.join()
        assertFalse(committed)
    }
}
