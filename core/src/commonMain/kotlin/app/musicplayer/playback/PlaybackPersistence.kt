package app.musicplayer.playback

import app.musicplayer.diagnostics.DiagnosticEvent
import app.musicplayer.library.LibraryRepository
import app.musicplayer.model.*
import app.musicplayer.queue.QueueController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Service lifetime storage boundary. Restore completes before collection can write anything. */
class PlaybackPersistence(
    private val repository: LibraryRepository,
    private val queue: QueueController,
    private val coordinator: PlaybackCoordinator,
    private val scope: CoroutineScope,
    clock: () -> Long,
    private val wallClock: () -> Long,
    private val diagnostics: (DiagnosticEvent) -> Unit = {},
) {
    private val mutableError = MutableStateFlow<AppError?>(null)
    val error: StateFlow<AppError?> = mutableError.asStateFlow()
    /**
     * Existing PlaybackPort consumers see storage failures without changing actual playback status.
     */
    val snapshot: StateFlow<PlaybackSnapshot> =
        combine(queue.snapshot, error) { actual, storageError ->
                actual.copy(error = actual.error ?: storageError)
            }
            .stateIn(scope, SharingStarted.Eagerly, queue.snapshot.value)
    private var restored = false
    private var collection: Job? = null
    private var history: Job? = null
    private val persistence =
        SnapshotPersistence(
            repository,
            clock,
            { queue.snapshot.value.status == PlayerStatus.PLAYING },
            onSaveError = { reportFailure("save") },
            onSaved = { clearFailure("save") },
        )

    suspend fun initialize() {
        if (!storage("restore") { queue.restore(repository.restoreQueue()) }) return
        restored = true
        storage("prune") { repository.prune(wallClock()) }
        collection = scope.launch { persistence.start(queue.state) }
        history = scope.launch {
            var startedAttempt: Long? = null
            coordinator.snapshot.collect { actual ->
                val attempt = coordinator.userAttempt.value
                if (
                    actual.status == PlayerStatus.PLAYING &&
                        actual.track != null &&
                        startedAttempt != attempt
                ) {
                    // Mark before suspending: repeated callbacks/resume cannot duplicate a history
                    // write.
                    startedAttempt = attempt
                    storage("history") { repository.recordStarted(actual.track.id, wallClock()) }
                }
            }
        }
    }

    suspend fun remember(track: Track): AppResult<Unit> = rememberAll(listOf(track))

    suspend fun rememberAll(tracks: List<Track>): AppResult<Unit> =
        if (storage("metadata") { repository.upsertAll(tracks) }) AppResult.Success(Unit)
        else AppResult.Failure(requireNotNull(error.value))

    suspend fun commitSourcePage(snapshot: QueueSnapshot, tracks: List<Track>): AppResult<Unit> =
        persistence.commitSourcePage(snapshot, tracks)

    suspend fun checkpoint() {
        if (restored) persistence.flush(queue.state.value)
    }

    suspend fun stop() {
        // Capture the actual position before stopping clears the engine's runtime snapshot.
        queue.stop { snapshot -> if (restored) persistence.flush(snapshot) }
    }

    suspend fun close() {
        collection?.cancelAndJoin()
        history?.cancelAndJoin()
        if (restored) persistence.flush(queue.state.value.copy(playWhenReady = false))
    }

    private suspend fun storage(stage: String, block: suspend () -> Unit): Boolean =
        try {
            block()
            clearFailure(stage)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            reportFailure(stage)
            false
        }

    private fun reportFailure(stage: String) {
        val failure = AppError(ErrorCode.DATABASE, "library.$stage", false)
        mutableError.value = failure
        diagnostics(DiagnosticEvent("library", stage, null, null, null, failure.code, 0))
    }

    private fun clearFailure(stage: String) {
        if (mutableError.value?.operationId == "library.$stage") mutableError.value = null
    }
}
