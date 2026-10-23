package app.musicplayer.ui.artists

import app.musicplayer.artists.*
import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ArtistMergeState(
    val merge: ArtistMergePreview? = null,
    val split: ArtistSplitPreview? = null,
    val saving: Boolean = false,
    val stale: Boolean = false,
    val error: AppError? = null,
)

class ArtistMergeModel(private val repository: ArtistRepository, ownerScope: CoroutineScope) {
    private val job = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + job)
    private val mutable = MutableStateFlow(ArtistMergeState())
    val state: StateFlow<ArtistMergeState> = mutable.asStateFlow()
    private var generation = 0L
    private var request: Job? = null

    fun preview(origin: ArtistId, target: ArtistId, source: SourceRef?) {
        closeDraft()
        command { selected ->
            val preview = repository.previewMerge(origin, target, source).required()
            ensureCurrent(selected) { mutable.update { it.copy(merge = preview) } }
        }
    }

    fun previewSplit(origin: ArtistId) {
        closeDraft()
        command { selected ->
            val preview = repository.previewSplit(origin).required()
            ensureCurrent(selected) { mutable.update { it.copy(split = preview) } }
        }
    }

    fun confirm() {
        val preview = state.value.merge ?: return
        command { selected ->
            when (repository.merge(preview).required()) {
                ArtistWriteResult.APPLIED ->
                    ensureCurrent(selected) { mutable.value = ArtistMergeState() }
                ArtistWriteResult.STALE -> {
                    val updated =
                        repository
                            .previewMerge(preview.origin, preview.target, preview.preferredSource)
                            .required()
                    ensureCurrent(selected) {
                        mutable.update { it.copy(merge = updated, stale = true) }
                    }
                }
            }
        }
    }

    fun confirmSplit() {
        val preview = state.value.split ?: return
        command { selected ->
            when (repository.split(preview).required()) {
                ArtistWriteResult.APPLIED ->
                    ensureCurrent(selected) { mutable.value = ArtistMergeState() }
                ArtistWriteResult.STALE -> {
                    val updated = repository.previewSplit(preview.origin).required()
                    ensureCurrent(selected) {
                        mutable.update { it.copy(split = updated, stale = true) }
                    }
                }
            }
        }
    }

    private fun command(action: suspend (Long) -> Unit) {
        if (state.value.saving) return
        val selected = ++generation
        mutable.update { it.copy(saving = true, error = null) }
        request = scope.launch {
            try {
                action(selected)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (selected == generation)
                    mutable.update {
                        it.copy(
                            error =
                                if (e is AppFailureException) e.error
                                else AppError(ErrorCode.UNKNOWN, "artists.merge.ui", true)
                        )
                    }
            } finally {
                if (selected == generation) mutable.update { it.copy(saving = false) }
            }
        }
    }

    private suspend fun ensureCurrent(selected: Long, action: () -> Unit) {
        currentCoroutineContext().ensureActive()
        if (selected == generation) action()
    }

    fun closeDraft() {
        generation++
        request?.cancel()
        mutable.value = ArtistMergeState()
    }

    fun close() {
        closeDraft()
        job.cancel()
    }

    private fun <T> AppResult<T>.required(): T =
        when (this) {
            is AppResult.Success -> value
            is AppResult.Failure -> throw AppFailureException(error)
        }
}
