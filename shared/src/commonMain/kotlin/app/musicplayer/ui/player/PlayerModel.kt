package app.musicplayer.ui.player

import app.musicplayer.catalog.TrackMetadataLoader
import app.musicplayer.library.LibraryRepository
import app.musicplayer.lyrics.LyricsLoader
import app.musicplayer.model.*
import app.musicplayer.queue.*
import app.musicplayer.ui.TrackMetadataRequests
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class PlayerModel(
    private val player: QueuePlaybackPort,
    private val library: LibraryRepository,
    private val lyrics: LyricsLoader,
    ownerScope: CoroutineScope,
    metadata: TrackMetadataLoader? = null,
) {
    private val job = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + job)
    private val metadataRequests = TrackMetadataRequests(metadata, scope)
    private val mutable =
        MutableStateFlow(PlayerState(snapshot = player.snapshot.value, queue = player.queue.value))
    val state: StateFlow<PlayerState> = mutable.asStateFlow()
    private var lookup: Job? = null
    private var lookupEntries: List<QueueEntry>? = null
    private var commandGeneration = 0L
    private var lyricsGeneration = 0L
    private var lyricsJob: Job? = null

    private data class MotionRequest(
        val from: QueueEntryId?,
        val to: QueueEntryId,
        val direction: TrackMotion,
    )

    private var motionRequest: MotionRequest? = null
    private var motionQueue = player.queue.value

    private data class LyricsBinding(
        val entryId: QueueEntryId?,
        val trackId: TrackId?,
        val candidateId: CandidateId?,
    )

    private var binding: LyricsBinding? = null

    init {
        scope.launch {
            player.snapshot.collect { snapshot ->
                val prior = mutable.value.snapshot.current?.id
                val next = snapshot.current?.id
                val changedEntry = prior != next
                val direction =
                    if (changedEntry) {
                        val requested = motionRequest
                        motionRequest = null
                        if (requested != null && requested.from == prior && requested.to == next)
                            requested.direction
                        else {
                            when (next) {
                                QueueOrder.next(motionQueue)?.id -> TrackMotion.NEXT
                                QueueOrder.previous(motionQueue)?.id -> TrackMotion.PREVIOUS
                                else -> TrackMotion.REPLACE
                            }
                        }
                    } else mutable.value.trackMotion
                motionQueue = player.queue.value
                val nextBinding =
                    LyricsBinding(snapshot.current?.id, snapshot.track?.id, snapshot.candidateId)
                val changed = binding != nextBinding
                if (changed) {
                    binding = nextBinding
                    ++lyricsGeneration
                    lyricsJob?.cancel()
                }
                mutable.update {
                    it.copy(
                        snapshot = snapshot,
                        trackMotion = direction,
                        queueTracks =
                            listOfNotNull(snapshot.track).associateBy { track -> track.id } +
                                it.queueTracks,
                        lyrics = if (changed) null else it.lyrics,
                        loadingLyrics = if (changed) false else it.loadingLyrics,
                        lyricsError = if (changed) null else it.lyricsError,
                    )
                }
                if (changed) fillMetadata()
                if (changed && nextBinding.candidateId != null) loadLyrics()
            }
        }
        scope.launch {
            player.queue.collect { queue ->
                if (queue.currentEntryId == mutable.value.snapshot.current?.id) motionQueue = queue
                mutable.update { it.copy(queue = queue) }
                if (queue.entries != lookupEntries) {
                    lookupEntries = queue.entries
                    lookupTracks()
                }
            }
        }
    }

    private fun lookupTracks() {
        lookup?.cancel()
        lookup = scope.launch {
            try {
                val records =
                    state.value.queue.entries
                        .map { it.trackId }
                        .distinct()
                        .mapNotNull { library.getTrack(it) }
                ensureActive()
                mutable.update { old ->
                    old.copy(
                        queueTracks =
                            (listOfNotNull(old.snapshot.track).associateBy { it.id } +
                                    records.associateBy { it.id })
                                .mapValues { (_, track) ->
                                    old.queueTracks[track.id]?.candidates?.fold(track) {
                                        current,
                                        known ->
                                        known.durationMs
                                            ?.takeIf { it > 0 }
                                            ?.let { current.withMissingDuration(known.source, it) }
                                            ?: current
                                    } ?: track
                                },
                        repositoryError = null,
                    )
                }
                fillMetadata()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ensureActive()
                mutable.update {
                    it.copy(
                        repositoryError =
                            AppError(ErrorCode.DATABASE, "player.queue.metadata", true)
                    )
                }
            }
        }
    }

    fun playPause() {
        ++commandGeneration
        val actual = state.value.snapshot
        if (actual.status == PlayerStatus.FAILED) {
            retry()
            return
        }
        if (
            actual.status == PlayerStatus.PLAYING ||
                (actual.playWhenReady &&
                    actual.status in setOf(PlayerStatus.RESOLVING, PlayerStatus.BUFFERING))
        )
            player.pause()
        else player.play()
    }

    fun next() = command {
        requestMotion(QueueCommand.Next, TrackMotion.NEXT)
        player.dispatch(QueueCommand.Next)
        AppResult.Success(Unit)
    }

    fun previous() = command {
        requestMotion(QueueCommand.Previous, TrackMotion.PREVIOUS)
        player.dispatch(QueueCommand.Previous)
        AppResult.Success(Unit)
    }

    fun seek(positionMs: Long) {
        ++commandGeneration
        player.seek(positionMs.coerceAtLeast(0))
    }

    fun retryMetadata() = lookupTracks()

    fun retryQueueSource() = command { player.retryQueueSource() }

    fun retry() {
        val snapshot = state.value.snapshot
        val track = state.value.currentTrack
        val entry = snapshot.current
        if (snapshot.status != PlayerStatus.FAILED || track == null || entry == null) {
            lookupTracks()
            return
        }
        command { player.select(track, entry, snapshot.positionMs, true) }
    }

    fun selectEntry(id: QueueEntryId) {
        val entry = state.value.queue.entries.find { it.id == id } ?: return
        val track = state.value.queueTracks[entry.trackId] ?: return
        command {
            motionRequest =
                MotionRequest(player.snapshot.value.current?.id, id, TrackMotion.REPLACE)
            player.select(track, entry, 0, true)
        }
    }

    private fun requestMotion(command: QueueCommand, direction: TrackMotion) {
        val queue = player.queue.value
        val target = QueueReducer.reduce(queue, command).snapshot.currentEntryId
        motionRequest =
            target
                ?.takeIf { it != queue.currentEntryId }
                ?.let {
                    MotionRequest(player.snapshot.value.current?.id, it, direction)
                }
    }

    fun toggleShuffle() = command {
        player.dispatch(QueueCommand.ToggleShuffle)
        AppResult.Success(Unit)
    }

    fun moveEntry(id: QueueEntryId, toIndex: Int) {
        if (!canMoveQueueEntry(player.queue.value, id, toIndex)) return
        command {
            player.dispatch(QueueCommand.MoveUpcoming(id, toIndex))
            AppResult.Success(Unit)
        }
    }

    fun removeEntry(id: QueueEntryId) = command {
        player.dispatch(QueueCommand.Remove(id))
        AppResult.Success(Unit)
    }

    fun loadLyrics() {
        val actual = state.value.snapshot
        val track = actual.track ?: return
        val candidate = track.candidates.find { it.id == actual.candidateId } ?: return
        if (state.value.loadingLyrics || state.value.lyrics is LyricsLookup.Available) return
        val selected = ++lyricsGeneration
        lyricsJob?.cancel()
        mutable.update { it.copy(loadingLyrics = true, lyricsError = null) }
        lyricsJob = scope.launch {
            try {
                val result = lyrics.load(track, candidate)
                ensureActive()
                if (selected == lyricsGeneration)
                    mutable.update {
                        it.copy(
                            lyrics = result,
                            lyricsError = (result as? LyricsLookup.Failed)?.error,
                        )
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (selected == lyricsGeneration)
                    mutable.update {
                        it.copy(lyricsError = AppError(ErrorCode.UNKNOWN, "player.lyrics", true))
                    }
            } finally {
                if (selected == lyricsGeneration) mutable.update { it.copy(loadingLyrics = false) }
            }
        }
    }

    private fun command(action: suspend () -> AppResult<Unit>) {
        val selected = ++commandGeneration
        mutable.update { it.copy(commandError = null) }
        scope.launch {
            try {
                val result = action()
                ensureActive()
                if (selected == commandGeneration)
                    mutable.update { it.copy(commandError = result.errorOrNull()) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (selected == commandGeneration)
                    mutable.update {
                        it.copy(commandError = AppError(ErrorCode.UNKNOWN, "player.command", true))
                    }
            }
        }
    }

    fun networkInvalidated() {
        metadataRequests.pause()
        ++lyricsGeneration
        lyricsJob?.cancel()
        mutable.update { it.copy(loadingLyrics = false) }
    }

    fun refreshMetadata() {
        metadataRequests.resume()
        fillMetadata()
    }

    private fun fillMetadata() {
        val current = state.value
        val tracks =
            (listOfNotNull(current.snapshot.track) + current.queueTracks.values)
                .distinctBy { it.id }
                .filter {
                    (it.durationMs ?: 0) <= 0 ||
                        it.candidates.any { candidate -> (candidate.durationMs ?: 0) <= 0 }
                }
        metadataRequests.load(tracks) { updated ->
            mutable.update {
                it.copy(queueTracks = it.queueTracks + updated.associateBy { track -> track.id })
            }
        }
    }

    fun close() {
        ++commandGeneration
        ++lyricsGeneration
        job.cancel()
    }
}
