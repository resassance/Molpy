package app.musicplayer.playback

import app.musicplayer.library.LibraryRepository
import app.musicplayer.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Saves durable queue identities/intent only. [clock] must be monotonic, in milliseconds. */
class SnapshotPersistence(
    private val repository: LibraryRepository,
    private val clock: () -> Long,
    private val actualPlaying: () -> Boolean,
    private val onSaveError: ((AppError) -> Unit)? = null,
    private val onSaved: () -> Unit = {},
) {
    private val mutex = Mutex()
    private var latest: QueueSnapshot? = null
    private var saved: QueueSnapshot? = null
    private var savedAtMs: Long? = null
    private var failedAtMs: Long? = null

    /**
     * Collect after restoration. Handled storage failures stay dirty and retry even while paused.
     */
    suspend fun start(queue: StateFlow<QueueSnapshot>): Unit = coroutineScope {
        val periodic = launch {
            while (true) {
                val waitMs = mutex.withLock {
                    val remaining = retryRemaining() ?: (INTERVAL_MS - elapsedSinceSave())
                    if (remaining > 0) remaining else INTERVAL_MS
                }
                delay(waitMs)
                mutex.withLock {
                    latest?.let { current ->
                        val retryDue = retryRemaining()?.let { it <= 0 } == true
                        val progressDue =
                            current != saved &&
                                current.playWhenReady &&
                                actualPlaying() &&
                                elapsedSinceSave() >= INTERVAL_MS
                        if (retryDue || (failedAtMs == null && progressDue)) save(current)
                    }
                }
            }
        }
        try {
            queue.collect { snapshot ->
                mutex.withLock {
                    val current = frozen(snapshot)
                    latest = current
                    val previous = saved
                    val transition =
                        previous == null ||
                            current.entries != previous.entries ||
                            current.currentEntryId != previous.currentEntryId ||
                            current.playWhenReady != previous.playWhenReady ||
                            current.shuffleEnabled != previous.shuffleEnabled ||
                            current.traversal != previous.traversal
                    val progressDue =
                        current.playWhenReady &&
                            actualPlaying() &&
                            elapsedSinceSave() >= INTERVAL_MS
                    val retryAllowed = retryRemaining()?.let { it <= 0 } ?: true
                    if (
                        current != previous &&
                            retryAllowed &&
                            (transition || !current.playWhenReady || progressDue)
                    )
                        save(current)
                }
            }
        } finally {
            periodic.cancel()
        }
    }

    /**
     * Explicit lifecycle/command checkpoint; serialized after any in-flight save, without
     * throttling.
     */
    suspend fun flush(snapshot: QueueSnapshot) {
        mutex.withLock {
            val current = frozen(snapshot)
            latest = current
            save(current)
        }
    }

    private fun elapsedSinceSave(): Long = savedAtMs?.let { (clock() - it).coerceAtLeast(0) } ?: 0

    private fun frozen(snapshot: QueueSnapshot) =
        snapshot.copy(
            entries = snapshot.entries.toList(),
            traversal =
                snapshot.traversal?.let {
                    it.copy(
                        history = it.history.toList(),
                        upcoming = it.upcoming.toList(),
                        priority = it.priority.toList(),
                    )
                },
        )

    private fun retryRemaining(): Long? = failedAtMs?.let {
        INTERVAL_MS - (clock() - it).coerceAtLeast(0)
    }

    // Default callers retain exception propagation. The service opts into safe normalized
    // reporting.
    private suspend fun save(snapshot: QueueSnapshot) {
        try {
            repository.saveQueue(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val report = onSaveError ?: throw failure
            failedAtMs = clock()
            report(AppError(ErrorCode.DATABASE, "library.save", false))
            return
        }
        saved = snapshot
        savedAtMs = clock()
        failedAtMs = null
        onSaved()
    }

    private companion object {
        const val INTERVAL_MS = 5000L
    }
}
