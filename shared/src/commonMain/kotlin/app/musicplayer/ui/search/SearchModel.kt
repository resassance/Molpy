package app.musicplayer.ui.search

import app.musicplayer.model.*
import app.musicplayer.queue.QueuePlaybackPort
import app.musicplayer.search.SearchCoordinator
import app.musicplayer.ui.components.LoadState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class SearchModel(
    private val search: SearchCoordinator,
    private val player: QueuePlaybackPort,
    ownerScope: CoroutineScope,
) {
    private val job = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + job)
    private val mutable = MutableStateFlow(LoadState<List<Track>>(emptyList()))
    val state: StateFlow<LoadState<List<Track>>> = mutable.asStateFlow()
    private val queryMutable = MutableStateFlow("")
    val query: StateFlow<String> = queryMutable.asStateFlow()
    private val commandMutable = MutableStateFlow<AppError?>(null)
    val commandError: StateFlow<AppError?> = commandMutable.asStateFlow()
    private var commandGeneration = 0L
    private var generation = 0L
    private var request: Job? = null

    fun search(query: String) {
        val selected = ++generation
        request?.cancel()
        queryMutable.value = query
        if (query.isBlank()) {
            mutable.value = LoadState(emptyList())
            return
        }
        mutable.value = mutable.value.copy(loading = true, error = null)
        request = scope.launch {
            try {
                val result = search.search(query.trim())
                ensureActive()
                if (selected != generation) return@launch
                mutable.value =
                    when (result) {
                        is AppResult.Success ->
                            LoadState(result.value.tracks, false, result.value.primaryFailure)
                        is AppResult.Failure ->
                            mutable.value.copy(loading = false, error = result.error)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (selected == generation)
                    mutable.value =
                        mutable.value.copy(error = AppError(ErrorCode.UNKNOWN, "search.ui", true))
            } finally {
                if (selected == generation) mutable.value = mutable.value.copy(loading = false)
            }
        }
    }

    fun play(index: Int) {
        val tracks = state.value.data.orEmpty().toList()
        if (index !in tracks.indices) return
        val selected = ++commandGeneration
        commandMutable.value = null
        scope.launch {
            val result =
                try {
                    player.playList(tracks, index)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    AppResult.Failure(AppError(ErrorCode.UNKNOWN, "search.play", true))
                }
            ensureActive()
            if (selected == commandGeneration) commandMutable.value = result.errorOrNull()
        }
    }

    fun networkInvalidated() {
        ++generation
        request?.cancel()
        mutable.value = mutable.value.copy(loading = false)
    }

    fun close() {
        ++generation
        ++commandGeneration
        job.cancel()
    }
}
