package app.musicplayer.catalog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PlaylistPageCache<K, V> {
    private val mutex = Mutex()
    private val values = linkedMapOf<K, V>()
    private val pending = mutableMapOf<K, CompletableDeferred<V>>()

    suspend fun get(key: K, refresh: Boolean = false, load: suspend () -> V): V {
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
        if (!owner) return deferred.await()
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
            deferred.completeExceptionally(failure)
            // The producer may be cancelled; clearing its slot must not depend on that job.
            mutex.withLock { if (pending[key] === deferred) pending.remove(key) }
            throw failure
        }
    }
}
