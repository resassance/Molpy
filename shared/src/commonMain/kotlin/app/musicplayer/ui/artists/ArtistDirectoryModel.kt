package app.musicplayer.ui.artists

import app.musicplayer.artists.*
import app.musicplayer.model.*
import app.musicplayer.ui.components.LoadState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ArtistDirectoryModel(repository: ArtistRepository, ownerScope: CoroutineScope) {
    private val job = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + job)
    private val mutable = MutableStateFlow(LoadState<ArtistDirectory>(loading = true))
    val state: StateFlow<LoadState<ArtistDirectory>> = mutable.asStateFlow()

    init {
        scope.launch {
            repository.directory
                .catch {
                    if (it is CancellationException) throw it
                    emit(AppResult.Failure(AppError(ErrorCode.DATABASE, "artists.observe", true)))
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

    fun close() {
        job.cancel()
    }
}
