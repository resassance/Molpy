package app.musicplayer.ui.library

import app.musicplayer.collections.*
import app.musicplayer.model.*
import app.musicplayer.queue.QueuePlaybackPort
import app.musicplayer.ui.components.LoadState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class LibraryModel(
    private val collections: CollectionsRepository,
    private val player: QueuePlaybackPort,
    ownerScope: CoroutineScope,
) {
    private val job = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + job)
    private val mutable = MutableStateFlow(LoadState<LibraryCollections>(loading = true))
    val state: StateFlow<LoadState<LibraryCollections>> = mutable.asStateFlow()
    private val errorMutable = MutableStateFlow<AppError?>(null)
    val commandError: StateFlow<AppError?> = errorMutable.asStateFlow()
    private var generation = 0L
    private var observer: Job? = null

    init {
        retry()
    }

    fun retry() {
        observer?.cancel()
        observer = scope.launch {
            collections.overview
                .catch {
                    if (it is CancellationException) throw it
                    emit(AppResult.Failure(AppError(ErrorCode.DATABASE, "library.observe", true)))
                }
                .collect { result ->
                    mutable.update { old ->
                        when (result) {
                            is AppResult.Success -> LoadState(result.value)
                            is AppResult.Failure -> old.copy(loading = false, error = result.error)
                        }
                    }
                }
        }
    }

    fun playlist(id: PlaylistId): Flow<LoadState<PlaylistDetail>> =
        collections
            .playlist(id)
            .runningFold(LoadState<PlaylistDetail>(loading = true)) { old, result ->
                when (result) {
                    is AppResult.Success -> LoadState(result.value)
                    is AppResult.Failure -> old.copy(loading = false, error = result.error)
                }
            }
            .catch {
                if (it is CancellationException) throw it
                emit(LoadState(error = AppError(ErrorCode.DATABASE, "playlist.observe", true)))
            }

    suspend fun create(name: String) = command { collections.create(name) }

    suspend fun rename(id: PlaylistId, name: String) = command { collections.rename(id, name) }

    suspend fun delete(id: PlaylistId) = command { collections.delete(id) }

    suspend fun append(id: PlaylistId, track: Track) = command { collections.append(id, track) }

    suspend fun move(id: PlaylistId, itemId: PlaylistItemId, toIndex: Int) = command {
        collections.move(id, itemId, toIndex)
    }

    suspend fun remove(id: PlaylistId, itemId: PlaylistItemId) = command {
        collections.remove(id, itemId)
    }

    fun play(tracks: List<Track>, index: Int) {
        val frozen = tracks.toList()
        if (index !in frozen.indices) return
        scope.launch { command { player.playList(frozen, index) } }
    }

    private suspend fun <T> command(action: suspend () -> AppResult<T>): AppResult<T> {
        val selected = ++generation
        val result =
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                AppResult.Failure(AppError(ErrorCode.DATABASE, "library.command", true))
            }
        currentCoroutineContext().ensureActive()
        if (selected == generation) errorMutable.value = result.errorOrNull()
        return result
    }

    fun close() {
        ++generation
        job.cancel()
    }
}
