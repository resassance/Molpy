package app.musicplayer.downloads

import app.musicplayer.model.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock

internal class DownloadPreparationQueue(
    private val gate: DownloadCommandGate,
    private val scope: CoroutineScope,
    private val errorFor: (Exception) -> AppError = {
        AppError(ErrorCode.UNKNOWN, "download.prepare", true)
    },
) {
    private data class Entry(
        val preparation: DownloadPreparation,
        val task: Deferred<AppResult<AssetId>>,
    )

    private val entries = mutableMapOf<DownloadPreparationKey, Entry>()
    private val mutable = MutableStateFlow<List<DownloadPreparation>>(emptyList())
    val states = mutable.asStateFlow()

    suspend fun <P> enqueue(
        track: Track,
        candidate: PlaybackCandidate,
        resolve: suspend () -> AppResult<P>,
        commit: suspend (P) -> AppResult<AssetId>,
    ): AppResult<AssetId> {
        val task =
            gate.mutex.withLock {
                submitLocked(track, candidate, resolve = resolve, commit = commit)
            }
        return task.await()
    }

    // Called under the owner's command mutex, including automatic restarts after cache removal.
    fun <P> submitLocked(
        track: Track,
        candidate: PlaybackCandidate,
        replace: Boolean = false,
        resolve: suspend () -> AppResult<P>,
        commit: suspend (P) -> AppResult<AssetId>,
    ): Deferred<AppResult<AssetId>> {
        val key = DownloadPreparationKey(track.id, candidate.id)
        entries[key]
            ?.takeIf { !replace && !it.task.isCompleted }
            ?.let {
                return it.task
            }
        val preparation = DownloadPreparation(UUID.randomUUID().toString(), track, candidate.id)
        val task =
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    val result =
                        try {
                            gate.prepareCurrent(
                                key,
                                { entries[key]?.preparation?.id == preparation.id },
                                resolve,
                            ) { value ->
                                commit(value).also {
                                    if (it is AppResult.Success) removeLocked(key, preparation.id)
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            AppResult.Failure(errorFor(failure))
                        }
                    gate.mutex.withLock {
                        entries[key]
                            ?.takeIf { it.preparation.id == preparation.id }
                            ?.let { entry ->
                                if (result is AppResult.Failure) {
                                    entries[key] =
                                        entry.copy(
                                            preparation = preparation.copy(error = result.error)
                                        )
                                    publish()
                                } else removeLocked(key, preparation.id)
                            }
                    }
                    result
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) {
                        gate.mutex.withLock { removeLocked(key, preparation.id) }
                    }
                    throw cancelled
                }
            }
        entries[key] = Entry(preparation, task)
        publish()
        task.start()
        return task
    }

    suspend fun cancel(id: String) =
        gate.mutex.withLock {
            entries.entries.firstOrNull { it.value.preparation.id == id }?.key?.let(::cancelLocked)
            Unit
        }

    fun cancelLocked(key: DownloadPreparationKey) {
        val entry = entries.remove(key) ?: return
        gate.invalidate(key)
        entry.task.cancel()
        publish()
    }

    fun cancelAllLocked() {
        entries.keys.toList().forEach(::cancelLocked)
    }

    private fun removeLocked(key: DownloadPreparationKey, id: String) {
        if (entries[key]?.preparation?.id != id) return
        entries.remove(key)
        publish()
    }

    private fun publish() {
        mutable.value = entries.values.map { it.preparation }
    }
}
