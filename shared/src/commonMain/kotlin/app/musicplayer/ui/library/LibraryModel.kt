package app.musicplayer.ui.library

import app.musicplayer.artists.*
import app.musicplayer.catalog.TrackMetadataLoader
import app.musicplayer.collections.*
import app.musicplayer.model.*
import app.musicplayer.queue.QueuePlaybackPort
import app.musicplayer.ui.TrackMetadataRequests
import app.musicplayer.ui.artists.withArtistRevision
import app.musicplayer.ui.components.LoadState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class LibraryModel(
    private val collections: CollectionsRepository,
    private val player: QueuePlaybackPort,
    ownerScope: CoroutineScope,
    private val artists: StateFlow<LoadState<ArtistDirectory>>? = null,
    metadata: TrackMetadataLoader? = null,
) {
    private val job = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + job)
    private val metadataRequests = TrackMetadataRequests(metadata, scope, initiallyVisible = false)
    private val playlistMetadata = TrackMetadataRequests(metadata, scope, initiallyVisible = false)
    private var playlistTracks = emptyList<Track>()
    private var playlistOwner: Any? = null
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
        metadataRequests.reset()
        playlistMetadata.reset()
        if (playlistOwner != null) playlistMetadata.load(playlistTracks) {}
        observer?.cancel()
        observer = scope.launch {
            collections.overview
                .withArtistRevision(artists) { it.artistRevision }
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
                    fillMetadata()
                }
        }
    }

    fun playlist(id: PlaylistId): Flow<LoadState<PlaylistDetail>> = flow {
        val owner = Any()
        playlistOwner = owner
        playlistMetadata.setVisible(true)
        try {
            emitAll(
                collections
                    .playlist(id)
                    .onEach { result ->
                        if (playlistOwner === owner)
                            result.valueOrNull()?.let { detail ->
                                playlistTracks = detail.items.map { it.track }
                                playlistMetadata.load(playlistTracks) {}
                            }
                    }
                    .runningFold(LoadState<PlaylistDetail>(loading = true)) { old, result ->
                        when (result) {
                            is AppResult.Success -> LoadState(result.value)
                            is AppResult.Failure -> old.copy(loading = false, error = result.error)
                        }
                    }
                    .catch {
                        if (it is CancellationException) throw it
                        emit(
                            LoadState(
                                error = AppError(ErrorCode.DATABASE, "playlist.observe", true)
                            )
                        )
                    }
            )
        } finally {
            if (playlistOwner === owner) {
                playlistOwner = null
                playlistTracks = emptyList()
                playlistMetadata.setVisible(false)
            }
        }
    }

    fun networkInvalidated() {
        metadataRequests.pause()
        playlistMetadata.pause()
    }

    fun refreshMetadata() {
        metadataRequests.resume()
        fillMetadata()
        playlistMetadata.resume()
        if (playlistOwner != null) playlistMetadata.load(playlistTracks) {}
    }

    private fun fillMetadata() {
        val data = state.value.data ?: return
        val tracks =
            data.favorites + data.albums.flatMap { it.tracks } + data.artists.flatMap { it.tracks }
        metadataRequests.load(tracks.filter { (it.durationMs ?: 0) <= 0 }) { updated ->
            val updates = updated.associateBy { it.id }
            mutable.update { old ->
                old.copy(
                    data =
                        old.data?.let { current ->
                            current.copy(
                                favorites = current.favorites.map { updates[it.id] ?: it },
                                albums =
                                    current.albums.map { album ->
                                        album.copy(
                                            tracks = album.tracks.map { updates[it.id] ?: it }
                                        )
                                    },
                                artists =
                                    current.artists.map { artist ->
                                        artist.copy(
                                            tracks = artist.tracks.map { updates[it.id] ?: it }
                                        )
                                    },
                            )
                        }
                )
            }
        }
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

    fun playShuffled(tracks: List<Track>) {
        val frozen = tracks.toList()
        if (frozen.isEmpty()) return
        scope.launch { command { player.playShuffledList(frozen) } }
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

    fun setMetadataVisible(visible: Boolean) {
        metadataRequests.setVisible(visible)
        if (visible) fillMetadata()
        else {
            playlistOwner = null
            playlistTracks = emptyList()
            playlistMetadata.setVisible(false)
        }
    }

    fun close() {
        ++generation
        job.cancel()
    }
}
