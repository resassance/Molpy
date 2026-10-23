package app.musicplayer.ui.profile

import app.musicplayer.artists.*
import app.musicplayer.listening.*
import app.musicplayer.model.*
import app.musicplayer.queue.QueuePlaybackPort
import app.musicplayer.ui.artists.withArtistRevision
import app.musicplayer.ui.components.LoadState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

fun formatListeningTime(playedMs: Long): String {
    val minutes = playedMs.coerceAtLeast(0) / 60000
    return if (minutes < 60) "$minutes мин" else "${minutes/60} ч ${minutes%60} мин"
}

class ProfileModel(
    private val repository: ListeningRepository,
    private val player: QueuePlaybackPort,
    ownerScope: CoroutineScope,
    private val artists: StateFlow<LoadState<ArtistDirectory>>? = null,
) {
    private val job = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + job)
    private val profileMutable = MutableStateFlow(LoadState<ListeningProfile>(loading = true))
    val profile: StateFlow<LoadState<ListeningProfile>> = profileMutable.asStateFlow()
    private val historyMutable = MutableStateFlow(LoadState<HistoryPage>())
    val history: StateFlow<LoadState<HistoryPage>> = historyMutable.asStateFlow()
    private val commandMutable = MutableStateFlow<AppError?>(null)
    val commandError: StateFlow<AppError?> = commandMutable.asStateFlow()
    private var commandGeneration = 0L
    private var observer: Job? = null
    private var historyJob: Job? = null
    private var historyGeneration = 0L

    init {
        observe()
        loadHistory(reset = true)
    }

    private fun observe() {
        observer?.cancel()
        observer = scope.launch {
            repository.profile
                .withArtistRevision(artists) { it.artistRevision }
                .catch {
                    if (it is CancellationException) throw it
                    emit(AppResult.Failure(AppError(ErrorCode.DATABASE, "profile.observe", true)))
                }
                .collect { result ->
                    profileMutable.update { old ->
                        when (result) {
                            is AppResult.Success -> LoadState(result.value)
                            is AppResult.Failure -> old.copy(loading = false, error = result.error)
                        }
                    }
                }
        }
    }

    fun loadHistory(reset: Boolean = false) {
        if (historyJob?.isActive == true && !reset) return
        val prior = history.value.data
        if (!reset && prior != null && prior.next == null) return
        val selected = ++historyGeneration
        historyJob?.cancel()
        val cursor = if (reset) null else prior?.next
        historyMutable.update { it.copy(loading = true, error = null) }
        historyJob = scope.launch {
            try {
                val result = repository.history(cursor, 50)
                ensureActive()
                if (selected != historyGeneration) return@launch
                historyMutable.update { old ->
                    when (result) {
                        is AppResult.Success ->
                            LoadState(
                                HistoryPage(
                                    (if (reset) emptyList() else prior?.items.orEmpty()) +
                                        result.value.items,
                                    result.value.next,
                                )
                            )
                        is AppResult.Failure -> old.copy(error = result.error)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (selected == historyGeneration)
                    historyMutable.update {
                        it.copy(error = AppError(ErrorCode.DATABASE, "history.load", true))
                    }
            } finally {
                if (selected == historyGeneration)
                    historyMutable.update { it.copy(loading = false) }
            }
        }
    }

    fun playHistory(index: Int, visibleCount: Int = Int.MAX_VALUE) =
        play(history.value.data?.items.orEmpty().take(visibleCount).map { it.track }, index)

    fun playRanking(index: Int) =
        play(profile.value.data?.tracks.orEmpty().take(5).map { it.track }, index)

    private fun play(tracks: List<Track>, index: Int) {
        val frozen = tracks.toList()
        if (index !in frozen.indices) return
        val selected = ++commandGeneration
        commandMutable.value = null
        scope.launch {
            val result =
                try {
                    player.playList(frozen, index)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    AppResult.Failure(AppError(ErrorCode.UNKNOWN, "profile.play", true))
                }
            ensureActive()
            if (selected == commandGeneration) commandMutable.value = result.errorOrNull()
        }
    }

    fun retry() {
        observe()
        loadHistory(reset = history.value.data == null)
    }

    fun close() {
        ++historyGeneration
        ++commandGeneration
        job.cancel()
    }
}
