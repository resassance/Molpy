package app.musicplayer.downloads

import app.musicplayer.model.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class DownloadPreparationKey(val track: TrackId, val candidate: CandidateId)

internal class DownloadCommandGate {
    val mutex = Mutex()
    private val pending = mutableMapOf<DownloadPreparationKey, Any>()

    // Owner commands invalidate under the same mutex that publishes prepared downloads.
    fun invalidate(key: DownloadPreparationKey) {
        pending.remove(key)
    }

    fun invalidateAll() {
        pending.clear()
    }

    suspend fun <P, T> prepare(
        key: DownloadPreparationKey,
        resolve: suspend () -> AppResult<P>,
        commit: suspend (P) -> AppResult<T>,
    ): AppResult<T> {
        return prepareCurrent(key, { true }, resolve, commit)
    }

    suspend fun <P, T> prepareCurrent(
        key: DownloadPreparationKey,
        isCurrent: () -> Boolean,
        resolve: suspend () -> AppResult<P>,
        commit: suspend (P) -> AppResult<T>,
    ): AppResult<T> {
        val token = Any()
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return obsolete()
            pending[key] = token
        }
        try {
            val resolved = resolve()
            return mutex.withLock {
                currentCoroutineContext().ensureActive()
                if (pending[key] !== token || !isCurrent()) obsolete()
                else
                    when (resolved) {
                        is AppResult.Success -> commit(resolved.value)
                        is AppResult.Failure -> resolved
                    }
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { if (pending[key] === token) pending.remove(key) }
            }
        }
    }

    private fun obsolete() =
        AppResult.Failure(AppError(ErrorCode.RESOURCE_CHANGED, "download.prepare", true))
}
