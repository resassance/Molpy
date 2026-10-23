package app.musicplayer.queue

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class QueueInsertionGate {
    private val mutex = Mutex()
    private var epoch = 0L

    fun invalidate() {
        epoch++
    }

    suspend fun <T> run(block: suspend (checkCurrent: () -> Unit) -> T): T {
        val accepted = epoch
        val caller = currentCoroutineContext()
        val check = {
            caller.ensureActive()
            if (accepted != epoch) throw CancellationException("Queue replaced")
        }
        return mutex.withLock {
            check()
            block(check)
        }
    }
}
