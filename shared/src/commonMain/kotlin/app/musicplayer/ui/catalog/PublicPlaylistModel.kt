package app.musicplayer.ui.catalog

import app.musicplayer.catalog.*
import app.musicplayer.model.*
import app.musicplayer.queue.PlaylistPlaybackRequest
import app.musicplayer.queue.QueuePlaybackPort
import app.musicplayer.ui.TrackMetadataRequests
import app.musicplayer.ui.components.LoadState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class PublicPlaylistScreenState(
    val ref: PublicPlaylistRef? = null,
    val card: PublicPlaylistCard? = null,
    val tracks: LoadState<CatalogPage<PlaylistOccurrence>> = LoadState(),
    val moreLoading: Boolean = false,
    val moreError: AppError? = null,
    val commandError: AppError? = null,
)

/** Browsing owns no queue lifecycle; only an explicit play assigns a source to the service. */
class PublicPlaylistModel(
    private val browse: BrowseCatalog,
    private val player: QueuePlaybackPort,
    ownerScope: CoroutineScope,
    metadata: TrackMetadataLoader? = null,
) {
    private val job = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + job)
    private val metadataRequests = TrackMetadataRequests(metadata, scope, initiallyVisible = false)
    private val mutable = MutableStateFlow(PublicPlaylistScreenState())
    val state: StateFlow<PublicPlaylistScreenState> = mutable.asStateFlow()
    private val cache = mutableMapOf<SourceRef, PublicPlaylistScreenState>()
    private var routeSource: SourceRef? = null
    private var generation = 0L
    private var commandGeneration = 0L
    private var request: Job? = null
    private var pageRequest: Job? = null
    private var networkRefreshPending = false
    private var metadataVisible = false

    fun open(ref: PublicPlaylistRef) = open(ref, refresh = false)

    fun refresh(ref: PublicPlaylistRef) = open(ref, refresh = true)

    private fun open(ref: PublicPlaylistRef, refresh: Boolean) {
        setMetadataVisible(true)
        if (
            !refresh &&
                routeSource == ref.source &&
                (request?.isActive == true || state.value.tracks.data != null)
        ) {
            fillMetadata()
            return
        }
        val selected = ++generation
        metadataRequests.reset()
        ++commandGeneration
        request?.cancel()
        pageRequest?.cancel()
        routeSource = ref.source
        val cached = if (refresh) null else cache[ref.source]
        mutable.value =
            cached?.copy(
                tracks = cached.tracks.copy(loading = false),
                moreLoading = false,
                commandError = null,
            ) ?: PublicPlaylistScreenState(ref = ref, tracks = LoadState(loading = true))
        fillMetadata()
        if (cached != null) return
        request = scope.launch {
            val result = attempt("playlist.load") { browse.playlist(ref, refresh) }
            ensureActive()
            if (selected != generation) return@launch
            when (result) {
                is AppResult.Success ->
                    update {
                        it.copy(
                            ref = result.value.card.ref,
                            card = result.value.card,
                            tracks = LoadState(result.value.tracks),
                        )
                    }
                is AppResult.Failure ->
                    update {
                        it.copy(tracks = it.tracks.copy(loading = false, error = result.error))
                    }
            }
        }
    }

    fun more() {
        val current = state.value
        val cursor = current.tracks.data?.next ?: return
        if (networkRefreshPending || current.tracks.loading || current.moreLoading) return
        val selected = generation
        update { it.copy(moreLoading = true, moreError = null) }
        pageRequest = scope.launch {
            // The same BrowseCatalog instance serves UI and service; cached service pages are
            // reused.
            val result = attempt("playlist.page") { browse.morePlaylist(cursor) }
            ensureActive()
            if (selected != generation) return@launch
            when (result) {
                is AppResult.Success ->
                    update {
                        it.copy(
                            tracks =
                                LoadState(
                                    CatalogPage(
                                        it.tracks.data?.items.orEmpty() + result.value.items,
                                        result.value.next,
                                    )
                                ),
                            moreLoading = false,
                            moreError = null,
                        )
                    }
                is AppResult.Failure ->
                    update {
                        it.copy(moreLoading = false, moreError = result.error)
                    }
            }
        }
    }

    fun retry() {
        if (state.value.moreError != null && state.value.tracks.error == null) more()
        else state.value.ref?.let { open(it, refresh = true) }
    }

    /** Ordinals identify occurrences, including unavailable rows and repeated TrackIds. */
    fun play(ordinal: Int) = startPlayback(ordinal, shuffle = false)

    fun playShuffled() {
        val ordinal =
            state.value.tracks.data
                ?.items
                ?.firstOrNull { !it.unavailable && it.track != null }
                ?.identity
                ?.ordinal ?: return
        startPlayback(ordinal, shuffle = true)
    }

    private fun startPlayback(ordinal: Int, shuffle: Boolean) {
        val current = state.value
        if (networkRefreshPending || current.tracks.loading) return
        val ref = current.ref ?: return
        val page = current.tracks.data ?: return
        val occurrence = page.items.firstOrNull { it.identity.ordinal == ordinal } ?: return
        if (occurrence.unavailable || occurrence.track == null) return
        val selected = generation
        val command = ++commandGeneration
        val playback =
            PlaylistPlaybackRequest(ref, page.items.toList(), ordinal, page.next, shuffle)
        update { it.copy(commandError = null) }
        scope.launch {
            val result = attempt("playlist.play") { player.playPublicPlaylist(playback) }
            ensureActive()
            if (selected == generation && command == commandGeneration)
                update { it.copy(commandError = result.errorOrNull()) }
        }
    }

    fun networkInvalidated() {
        metadataRequests.pause()
        ++generation
        ++commandGeneration
        networkRefreshPending = true
        request?.cancel()
        pageRequest?.cancel()
        cache.clear()
        mutable.value =
            state.value.copy(
                tracks = LoadState(error = AppError(ErrorCode.NETWORK, "playlist.route", true)),
                moreLoading = false,
                moreError = null,
                commandError = null,
            )
    }

    fun networkActivated() {
        metadataRequests.resume()
        if (!networkRefreshPending) return
        networkRefreshPending = false
        if (metadataVisible) state.value.ref?.let { open(it, refresh = true) }
    }

    fun setMetadataVisible(visible: Boolean) {
        metadataVisible = visible
        metadataRequests.setVisible(visible)
        if (visible) fillMetadata()
    }

    fun close() {
        ++generation
        ++commandGeneration
        job.cancel()
    }

    private fun fillMetadata() {
        metadataRequests.load(state.value.tracks.data?.items.orEmpty().mapNotNull { it.track }) {
            tracks ->
            val updates = tracks.associateBy { it.id }
            update { old ->
                old.copy(
                    tracks =
                        old.tracks.copy(
                            data =
                                old.tracks.data?.let { page ->
                                    page.copy(
                                        items =
                                            page.items.map { row ->
                                                row.copy(
                                                    track = row.track?.let { updates[it.id] ?: it }
                                                )
                                            }
                                    )
                                }
                        )
                )
            }
        }
    }

    private fun update(transform: (PublicPlaylistScreenState) -> PublicPlaylistScreenState) {
        mutable.value = transform(state.value)
        if (state.value.tracks.data != null) routeSource?.let { cache[it] = state.value }
        fillMetadata()
    }

    private suspend fun <T> attempt(
        operation: String,
        block: suspend () -> AppResult<T>,
    ): AppResult<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            AppResult.Failure(AppError(ErrorCode.UNKNOWN, operation, true))
        }
}
