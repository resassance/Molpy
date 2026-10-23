package app.musicplayer.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class PlaylistPageCache<K, V> {
    private val mutex = Mutex()
    private val values = linkedMapOf<K, V>()
    private val pending = mutableMapOf<K, CompletableDeferred<V>>()

    suspend fun get(key: K, refresh: Boolean = false, load: suspend () -> V): V {
        while (true) {
            currentCoroutineContext().ensureActive()
            var owner = false
            val deferred = mutex.withLock {
                if (!refresh && values.containsKey(key)) return values.getValue(key)
                val existing = pending[key]
                if (!refresh && existing != null) existing
                else
                    CompletableDeferred<V>().also {
                        owner = true
                        pending[key] = it
                    }
            }
            if (!owner) {
                try {
                    return deferred.await()
                } catch (_: CancellationException) {
                    // Closing the producer's screen must not cancel an independent service caller.
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }
            try {
                val value = load()
                mutex.withLock {
                    if (pending[key] === deferred) {
                        values[key] = value
                        while (values.size > 128) values.remove(values.keys.first())
                        pending.remove(key)
                    }
                }
                deferred.complete(value)
                return value
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    // Remove the cancelled slot before waking callers that may retry immediately.
                    mutex.withLock { if (pending[key] === deferred) pending.remove(key) }
                    deferred.completeExceptionally(failure)
                }
                throw failure
            }
        }
    }
}
