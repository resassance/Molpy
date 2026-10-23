package app.musicplayer.ui.search

import app.musicplayer.catalog.*
import app.musicplayer.model.*
import app.musicplayer.queue.QueuePlaybackPort
import app.musicplayer.search.MusicSearchCoordinator
import app.musicplayer.search.SearchCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class SearchScrollPosition(val index: Int = 0, val offset: Int = 0)

data class SearchScreenState(
    val query: String = "",
    val filter: SearchFilter = SearchFilter.ALL,
    val page: SearchPage? = null,
    val loading: Boolean = false,
    val error: AppError? = null,
    val loadingMore: Boolean = false,
    val moreError: AppError? = null,
    val scroll: SearchScrollPosition = SearchScrollPosition(),
)

class SearchModel(
    private val search: MusicSearchCoordinator,
    private val player: QueuePlaybackPort,
    ownerScope: CoroutineScope,
) {
    constructor(
        search: SearchCoordinator,
        player: QueuePlaybackPort,
        ownerScope: CoroutineScope,
    ) : this(MusicSearchCoordinator(EmptyMusicSearch, search), player, ownerScope)

    private val job = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + job)
    private val mutable = MutableStateFlow(SearchScreenState())
    val state: StateFlow<SearchScreenState> = mutable.asStateFlow()
    private val queryMutable = MutableStateFlow("")
    val query: StateFlow<String> = queryMutable.asStateFlow()
    private val commandMutable = MutableStateFlow<AppError?>(null)
    val commandError: StateFlow<AppError?> = commandMutable.asStateFlow()
    private val cache = mutableMapOf<SearchFilter, SearchScreenState>()
    private var generation = 0L
    private var commandGeneration = 0L
    private var request: Job? = null
    private var moreRequest: Job? = null
    private var paginated = false

    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed == state.value.query) return
        cancelRequests()
        cache.clear()
        queryMutable.value = trimmed
        mutable.value = SearchScreenState(query = trimmed, filter = state.value.filter)
        if (trimmed.isNotEmpty()) load()
    }

    fun selectFilter(filter: SearchFilter) {
        if (filter == state.value.filter) return
        cancelRequests()
        val current = state.value
        cache[current.filter] = current.copy(loading = false, loadingMore = false)
        val cached = cache[filter]
        mutable.value = cached ?: SearchScreenState(query = current.query, filter = filter)
        if (current.query.isNotEmpty() && cached?.page == null) load()
    }

    fun retry() {
        if (state.value.query.isBlank()) return
        cancelRequests()
        load()
    }

    private fun cancelRequests() {
        ++generation
        ++commandGeneration
        commandMutable.value = null
        request?.cancel()
        moreRequest?.cancel()
        paginated = false
    }

    private fun update(transform: (SearchScreenState) -> SearchScreenState) {
        mutable.value = transform(state.value)
        cache[state.value.filter] = state.value
    }

    private fun load() {
        val selected = generation
        val target = state.value
        update { it.copy(loading = true, error = null, loadingMore = false, moreError = null) }
        fun publish(page: SearchPage) {
            if (selected != generation) return
            update {
                it.copy(
                    page = if (paginated) refresh(it.page, page) else page,
                    loading = false,
                    error = page.primaryFailure,
                )
            }
        }
        request = scope.launch {
            try {
                val result = search.search(target.query, target.filter, ::publish)
                ensureActive()
                if (selected != generation) return@launch
                when (result) {
                    is AppResult.Success -> publish(result.value)
                    is AppResult.Failure ->
                        update { it.copy(loading = false, error = result.error) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (selected == generation)
                    update {
                        it.copy(
                            loading = false,
                            error = AppError(ErrorCode.UNKNOWN, "search.ui", true),
                        )
                    }
            }
        }
    }

    fun more() = loadMore(retry = false)

    fun retryMore() = loadMore(retry = true)

    private fun loadMore(retry: Boolean) {
        val current = state.value
        val cursor = current.page?.next ?: return
        if (
            current.loading ||
                moreRequest?.isActive == true ||
                (!retry && current.moreError != null)
        )
            return
        val selected = generation
        paginated = true
        update { it.copy(loadingMore = true, moreError = null) }
        moreRequest = scope.launch {
            try {
                val result = search.more(cursor)
                ensureActive()
                if (selected != generation) return@launch
                when (result) {
                    is AppResult.Success ->
                        update {
                            it.copy(
                                page = append(requireNotNull(it.page), result.value),
                                loadingMore = false,
                                moreError = null,
                            )
                        }
                    is AppResult.Failure ->
                        update { it.copy(loadingMore = false, moreError = result.error) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (selected == generation)
                    update {
                        it.copy(
                            loadingMore = false,
                            moreError = AppError(ErrorCode.UNKNOWN, "search.more", true),
                        )
                    }
            }
        }
    }

    fun updateScroll(
        index: Int,
        offset: Int,
        query: String = state.value.query,
        filter: SearchFilter = state.value.filter,
    ) {
        if (query != state.value.query || filter != state.value.filter) return
        update {
            it.copy(scroll = SearchScrollPosition(index.coerceAtLeast(0), offset.coerceAtLeast(0)))
        }
    }

    fun play(key: String) {
        val items = state.value.page?.items().orEmpty().filterIsInstance<SearchItem.TrackItem>()
        val index = items.indexOfFirst { it.key == key }
        if (index < 0) return
        val tracks = items.map { it.track }
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
        cancelRequests()
        cache.keys.toList().forEach { filter ->
            val previous = cache.getValue(filter)
            cache[filter] =
                previous.copy(
                    page = previous.page?.copy(next = null),
                    loading = false,
                    loadingMore = false,
                    moreError = null,
                )
        }
        mutable.value =
            cache[state.value.filter] ?: state.value.copy(loading = false, loadingMore = false)
    }

    fun close() {
        ++generation
        ++commandGeneration
        job.cancel()
    }

    private fun refresh(current: SearchPage?, initial: SearchPage): SearchPage {
        if (current == null) return initial
        val updates = initial.items().associateBy { it.key }
        return current.copy(
            topResult = current.topResult?.let { updates[it.key] ?: it },
            sections =
                current.sections.map { section ->
                    section.copy(items = section.items.map { updates[it.key] ?: it })
                },
            primaryFailure = initial.primaryFailure,
        )
    }

    private fun append(current: SearchPage, next: SearchPage): SearchPage {
        val seen = current.items().mapTo(mutableSetOf()) { it.key }
        val sections = current.sections.toMutableList()
        val incoming =
            listOfNotNull(next.topResult?.let { SearchSection(it.filter, listOf(it)) }) +
                next.sections
        incoming.forEach { section ->
            val unique = section.items.filter { seen.add(it.key) }
            if (unique.isNotEmpty()) {
                if (sections.lastOrNull()?.filter == section.filter) {
                    val last = sections.removeAt(sections.lastIndex)
                    sections += last.copy(items = last.items + unique)
                } else sections += section.copy(items = unique)
            }
        }
        return current.copy(sections = sections, next = next.next)
    }
}

private object EmptyMusicSearch : MusicSearchPort {
    override suspend fun search(query: String, filter: SearchFilter) =
        AppResult.Success(SearchPage(null, emptyList(), null))

    override suspend fun more(cursor: CatalogCursor): AppResult<SearchPage> =
        AppResult.Failure(AppError(ErrorCode.SOURCE_UNAVAILABLE, "search.more", false))
}
