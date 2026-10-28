package app.musicplayer.downloads

import app.musicplayer.model.*
import kotlinx.coroutines.NonCancellable
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
        val token = Any()
        mutex.withLock { pending[key] = token }
        try {
            val resolved = resolve()
            return mutex.withLock {
                if (pending[key] !== token)
                    AppResult.Failure(
                        AppError(ErrorCode.RESOURCE_CHANGED, "download.prepare", true)
                    )
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
}
