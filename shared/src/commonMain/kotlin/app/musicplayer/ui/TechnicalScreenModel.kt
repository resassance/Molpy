package app.musicplayer.ui

import app.musicplayer.lyrics.LyricsLoader
import app.musicplayer.model.*
import app.musicplayer.playback.PlaybackPort
import app.musicplayer.search.SearchCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Dispatcher-confined UI state. Its child jobs belong to this screen, playback belongs to the service. */
class TechnicalScreenModel(
    private val searchCoordinator: SearchCoordinator,
    private val player: PlaybackPort,
    private val lyricsLoader: LyricsLoader,
    ownerScope: CoroutineScope,
) {
    private val scope = CoroutineScope(ownerScope.coroutineContext + SupervisorJob(ownerScope.coroutineContext[Job]))
    private val stateMutable = MutableStateFlow(TechnicalState(
        selected = player.snapshot.value.track, playback = player.snapshot.value,
    ))
    val state: StateFlow<TechnicalState> = stateMutable.asStateFlow()
    private var searchGeneration = 0L
    private var selectionGeneration = 0L
    private var expectedEntry: QueueEntry? = null
    private var awaitingSelection = false
    private var searchJob: Deferred<Unit>? = null
    private var selectionJob: Job? = null
    private var lyricsJob: Job? = null
    private var closed = false

    init {
        scope.launch {
            player.snapshot.collect { actual ->
                if (awaitingSelection && actual.current != expectedEntry) return@collect
                if (actual.current == expectedEntry) awaitingSelection = false
                val changed = actual.track?.id != stateMutable.value.selected?.id
                if (changed && actual.track != null) {
                    ++selectionGeneration
                    lyricsJob?.cancel()
                }
                stateMutable.update { old -> old.copy(
                    selected = actual.track ?: old.selected,
                    playback = actual,
                    lyrics = if (changed && actual.track != null) null else old.lyrics,
                    loadingLyrics = if (changed && actual.track != null) false else old.loadingLyrics,
                ) }
            }
        }
    }

    suspend fun search(query: String) {
        if (closed) return
        val generation = ++searchGeneration
        searchJob?.cancel()
        stateMutable.update { it.copy(query = query, loadingSearch = true, searchError = null) }
        searchJob = scope.async {
            val result = searchCoordinator.search(query)
            if (generation != searchGeneration || closed) return@async
            stateMutable.update { old -> when (result) {
                is AppResult.Success -> old.copy(results = result.value.tracks, loadingSearch = false, searchError = result.value.primaryFailure)
                is AppResult.Failure -> old.copy(results = emptyList(), loadingSearch = false, searchError = result.error)
            } }
        }
        searchJob?.await()
    }

    fun select(track: Track) {
        if (closed) return
        val generation = ++selectionGeneration
        selectionJob?.cancel()
        lyricsJob?.cancel()
        val entry = QueueEntry(QueueEntryId("technical-$generation"), track.id)
        expectedEntry = entry
        awaitingSelection = true
        stateMutable.update { it.copy(selected = track, lyrics = null, loadingLyrics = false,
            playback = PlaybackSnapshot(PlayerStatus.RESOLVING, entry, track, playWhenReady = true)) }
        selectionJob = scope.launch {
            val result = player.select(track, entry)
            if (generation != selectionGeneration || closed) return@launch
            awaitingSelection = false
            if (result is AppResult.Failure) stateMutable.update { it.copy(
                playback = it.playback.copy(status = PlayerStatus.FAILED, error = result.error),
            ) }
        }
    }

    fun loadLyrics() {
        if (closed || stateMutable.value.loadingLyrics) return
        val track = stateMutable.value.selected ?: return
        val candidate = track.candidates.firstOrNull()
        if (candidate == null) {
            stateMutable.update { it.copy(lyrics = LyricsLookup.Unavailable) }
            return
        }
        val generation = selectionGeneration
        lyricsJob?.cancel()
        stateMutable.update { it.copy(loadingLyrics = true) }
        lyricsJob = scope.launch {
            val result = lyricsLoader.load(track, candidate)
            if (generation == selectionGeneration && !closed) stateMutable.update { it.copy(lyrics = result, loadingLyrics = false) }
        }
    }

    fun playPause() {
        if (closed) return
        val snapshot = stateMutable.value.playback
        if (snapshot.status == PlayerStatus.FAILED || snapshot.status == PlayerStatus.ENDED) {
            stateMutable.value.selected?.let(::select)
        } else if (snapshot.playWhenReady || snapshot.status == PlayerStatus.PLAYING) player.pause() else player.play()
    }

    fun seek(positionMs: Long) {
        if (!closed) player.seek(positionMs.coerceAtLeast(0).let { value ->
            stateMutable.value.playback.durationMs?.let { value.coerceAtMost(it.coerceAtLeast(0)) } ?: value
        })
    }

    fun close() {
        if (closed) return
        closed = true
        ++searchGeneration
        ++selectionGeneration
        scope.cancel()
    }
}
