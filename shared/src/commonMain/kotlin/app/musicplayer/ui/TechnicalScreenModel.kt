package app.musicplayer.ui

import app.musicplayer.downloads.*
import app.musicplayer.lyrics.LyricsLoader
import app.musicplayer.model.*
import app.musicplayer.queue.*
import app.musicplayer.library.LibraryRepository
import app.musicplayer.network.*
import app.musicplayer.search.SearchCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.uuid.Uuid

/** Dispatcher-confined UI state. Its child jobs belong to this screen, playback belongs to the service. */
class TechnicalScreenModel(
    private val searchCoordinator: SearchCoordinator,
    private val player: QueuePlaybackPort,
    private val lyricsLoader: LyricsLoader,
    private val library: LibraryRepository,
    private val network: NetworkController,
    private val secrets: SecretStore,
    ownerScope: CoroutineScope,
    private val downloads: DownloadPort? = null,
    private val measureStorage: suspend () -> StorageUsageSnapshot = { StorageUsageSnapshot(0, emptyMap()) },
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
    private var networkEditRevision = 0L
    private var credentialRef: SecretId? = null
    private var credentialUsername: String? = null
    private val credentialRefsForCleanup = mutableSetOf<SecretId>()
    // Command retry never owns repository subscriptions, passwords or playback selection work.
    private var failedCommandRetry: (() -> Unit)? = null
    private var commandGeneration = 0L
    private enum class RepositorySource { QUEUE, FAVORITES, RECENT }
    private val failedRepositorySources = mutableSetOf<RepositorySource>()
    private val repositoryJobs = mutableMapOf<RepositorySource, Job>()

    init {
        downloads?.let { port ->
            scope.launch { port.states.collect { assets ->
                stateMutable.update { it.copy(downloads = assets) }
                refreshStorage()
            } }
            scope.launch { port.failures.collect { failures -> stateMutable.update { it.copy(downloadFailures = failures) } } }
        }
        RepositorySource.entries.forEach(::observeRepository)
        scope.launch {
            val revision = 0L // Initial restore must not overwrite edits made before this job starts.
            try {
                val config = library.getNetworkConfig()
                config.credentialRef?.let(credentialRefsForCleanup::add)
                if (revision == networkEditRevision && !closed) {
                    credentialRef = config.credentialRef
                    stateMutable.update { it.copy(networkDraft = NetworkDraft(config.mode,
                        config.endpoint?.host.orEmpty(), config.endpoint?.port?.toString().orEmpty(), config.credentialRef != null),
                        networkHasCredential = config.credentialRef != null) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { stateMutable.update { it.copy(networkError = AppError(ErrorCode.DATABASE, "settings.load", true)) } }
        }
        scope.launch {
            player.snapshot.collect { actual ->
                if (awaitingSelection && actual.current != expectedEntry) return@collect
                if (actual.current == expectedEntry) awaitingSelection = false
                val changed = actual.track != stateMutable.value.selected || actual.candidateId != stateMutable.value.playback.candidateId
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
        val operation = scope.async {
            try {
                val result = searchCoordinator.search(query)
                ensureActive()
                if (generation != searchGeneration || closed) return@async
                stateMutable.update { old -> when (result) {
                    is AppResult.Success -> old.copy(results = result.value.tracks, searchError = result.value.primaryFailure)
                    is AppResult.Failure -> old.copy(results = emptyList(), searchError = result.error)
                } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (generation == searchGeneration && !closed) stateMutable.update {
                    it.copy(searchError = AppError(ErrorCode.UNKNOWN, "ui.search", true))
                }
            } finally {
                if (generation == searchGeneration && !closed) stateMutable.update { it.copy(loadingSearch = false) }
            }
        }
        searchJob = operation
        try { operation.await() }
        catch (e: CancellationException) {
            operation.cancel()
            if (generation == searchGeneration && !closed) stateMutable.update { it.copy(loadingSearch = false) }
            throw e
        }
    }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    fun select(track: Track) {
        if (closed) return
        startSelection(track, QueueEntry(QueueEntryId(Uuid.random().toString()), track.id), 0)
    }

    private fun startSelection(track: Track, entry: QueueEntry, positionMs: Long) {
        val generation = ++selectionGeneration
        selectionJob?.cancel()
        lyricsJob?.cancel()
        expectedEntry = entry
        awaitingSelection = true
        stateMutable.update { it.copy(selected = track, lyrics = null, loadingLyrics = false,
            playback = PlaybackSnapshot(PlayerStatus.RESOLVING, entry, track, positionMs, playWhenReady = true)) }
        selectionJob = scope.launch {
            val result = player.select(track, entry, positionMs)
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
        val candidate = stateMutable.value.playback.candidateId?.let { id -> track.candidates.find { it.id == id } }
            ?: track.candidates.firstOrNull()
        if (candidate == null) {
            stateMutable.update { it.copy(lyrics = LyricsLookup.Unavailable) }
            return
        }
        val generation = selectionGeneration
        lyricsJob?.cancel()
        stateMutable.update { it.copy(loadingLyrics = true) }
        lyricsJob = scope.launch {
            try {
                val result = lyricsLoader.load(track, candidate)
                ensureActive()
                if (generation == selectionGeneration && !closed) stateMutable.update { it.copy(lyrics = result) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (generation == selectionGeneration && !closed) stateMutable.update {
                    it.copy(lyrics = LyricsLookup.Failed(AppError(ErrorCode.UNKNOWN, "ui.lyrics", true)))
                }
            } finally {
                if (generation == selectionGeneration && !closed) stateMutable.update { it.copy(loadingLyrics = false) }
            }
        }
    }

    fun playPause() {
        if (closed) return
        val snapshot = stateMutable.value.playback
        if (snapshot.status == PlayerStatus.FAILED || snapshot.status == PlayerStatus.ENDED) {
            retry()
        } else if (snapshot.playWhenReady || snapshot.status == PlayerStatus.PLAYING) player.pause() else player.play()
    }

    fun seek(positionMs: Long) {
        if (!closed) player.seek(positionMs.coerceAtLeast(0).let { value ->
            stateMutable.value.playback.durationMs?.let { value.coerceAtMost(it.coerceAtLeast(0)) } ?: value
        })
    }


    /** Failed retry keeps occurrence/position; ended replay uses the same occurrence from zero. */
    fun retry() {
        if (closed) return
        val actual = stateMutable.value.playback
        val entry = actual.current ?: return
        val track = actual.track ?: stateMutable.value.selected ?: return
        startSelection(track, entry, if (actual.status == PlayerStatus.ENDED) 0 else actual.positionMs)
    }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    fun addToQueue(track: Track) = command {
        library.upsert(track)
        player.dispatch(QueueCommand.Add(listOf(QueueEntry(QueueEntryId(Uuid.random().toString()), track.id))))
    }
    fun removeQueue(id: QueueEntryId) = command { player.dispatch(QueueCommand.Remove(id)) }
    fun selectQueue(id: QueueEntryId) = command { player.dispatch(QueueCommand.Select(id)) }
    fun next() = command { player.dispatch(QueueCommand.Next) }
    fun previous() = command { player.dispatch(QueueCommand.Previous) }
    fun setFavorite(track: Track, favorite: Boolean) = command { library.upsert(track); library.setFavorite(track.id, favorite) }

    fun download(track: Track) = command {
        val candidate = stateMutable.value.playback.takeIf { it.track == track }?.candidateId
            ?.let { id -> track.candidates.find { it.id == id } } ?: track.candidates.firstOrNull()
            ?: throw AppFailureException(AppError(ErrorCode.SOURCE_UNAVAILABLE, "download.candidate", false))
        downloads?.enqueue(track, candidate)?.errorOrNull()?.let { throw AppFailureException(it) }
    }
    fun pauseDownload(id: AssetId) = downloadCommand { pause(id) }
    fun resumeDownload(id: AssetId) = downloadCommand { resume(id) }
    fun cancelDownload(id: AssetId) = downloadCommand { cancel(id) }
    fun deleteDownload(id: AssetId) = downloadCommand { remove(id) }
    fun removeAllDownloads() = downloadCommand { removeAll() }
    private fun downloadCommand(action: suspend DownloadPort.() -> AppResult<Unit>) = command {
        downloads?.let { port -> action(port).errorOrNull()?.let { throw AppFailureException(it) } }
        refreshStorage()
    }
    suspend fun refreshStorage() {
        try { val usage = measureStorage(); stateMutable.update { it.copy(storageUsage = usage) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { stateMutable.update { it.copy(commandError = AppError(ErrorCode.DATABASE, "storage.measure", true)) } }
    }

    private fun command(action: suspend () -> Unit) {
        if (closed) return
        val generation = ++commandGeneration
        failedCommandRetry = null
        stateMutable.update { it.copy(commandError = null) }
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == commandGeneration && !closed) {
                    failedCommandRetry = { command(action) }
                    val error = (e as? AppFailureException)?.error ?: AppError(ErrorCode.DATABASE, "ui.command", true)
                    stateMutable.update { it.copy(commandError = error) }
                }
            }
        }
    }

    private fun observeRepository(source: RepositorySource) {
        if (closed || repositoryJobs[source]?.isActive == true) return
        repositoryJobs[source] = scope.launch {
            try {
                when (source) {
                    RepositorySource.QUEUE -> player.queue.collectLatest { queue ->
                        stateMutable.update { it.copy(queue = queue) }
                        val tracks = queue.entries.mapNotNull { library.getTrack(it.trackId) }.associateBy { it.id }
                        stateMutable.update { it.copy(queueTracks = tracks) }
                        repositoryRecovered(source)
                    }
                    RepositorySource.FAVORITES -> library.favorites.collect { tracks ->
                        stateMutable.update { it.copy(favorites = tracks) }
                        repositoryRecovered(source)
                    }
                    RepositorySource.RECENT -> library.recent.collect { tracks ->
                        stateMutable.update { it.copy(recent = tracks) }
                        repositoryRecovered(source)
                    }
                }
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (!closed) {
                    failedRepositorySources += source
                    stateMutable.update { it.copy(repositoryError = AppError(ErrorCode.DATABASE, "library.observe", true)) }
                }
            }
        }
    }

    private fun repositoryRecovered(source: RepositorySource) {
        if (failedRepositorySources.remove(source) && failedRepositorySources.isEmpty()) {
            stateMutable.update { it.copy(repositoryError = null) }
        }
    }

    /** Explicitly restart every failed source; keep its error until fresh data arrives. */
    fun retryRepository() {
        if (!closed) failedRepositorySources.toList().forEach(::observeRepository)
    }

    fun editNetwork(draft: NetworkDraft) {
        if (closed || stateMutable.value.networkApplying) return
        ++networkEditRevision
        stateMutable.update { it.copy(networkDraft = draft, networkMessage = null, networkError = null) }
    }

    suspend fun applyNetwork(password: CharArray?) {
        var ownsApply = false
        try {
            if (closed || stateMutable.value.networkApplying) return
            ++networkEditRevision
            val draft = stateMutable.value.networkDraft
            val host = draft.host.trim()
            val port = draft.port.trim().toIntOrNull()
            if (draft.mode != NetworkMode.SYSTEM && (!validHost(host) || port == null || port !in 1..65535)) {
                stateMutable.update { it.copy(networkMessage = "Укажите имя узла без схемы и пути и порт от 1 до 65535.") }
                return
            }
            val auth = draft.mode == NetworkMode.HTTP_CONNECT && draft.authentication
            if (auth && ((password?.isNotEmpty() == true && draft.username.isBlank()) ||
                ((password == null || password.isEmpty()) && (credentialRef == null || (draft.username.isNotBlank() && draft.username != credentialUsername))))) {
                stateMutable.update { it.copy(networkMessage = "Укажите имя пользователя и новый пароль прокси.") }
                return
            }
            ownsApply = true
            stateMutable.update { it.copy(networkApplying = true, networkMessage = null, networkError = null) }
            credentialRef?.let(credentialRefsForCleanup::add)
            val newRef = if (!auth) null else if (password?.isNotEmpty() == true) secrets.put(draft.username, password) else credentialRef
            newRef?.let(credentialRefsForCleanup::add)
            // Keep a created reference even if apply fails after persisting it. Never delete a referenced secret.
            if (auth) { credentialRef = newRef; if (password?.isNotEmpty() == true) credentialUsername = draft.username }
            val config = NetworkConfig(draft.mode, if (draft.mode == NetworkMode.SYSTEM) null else ProxyEndpoint(host, port!!), newRef)
            when (val result = network.apply(config)) {
                is AppResult.Success -> {
                    credentialRef = newRef
                    stateMutable.update { it.copy(networkHasCredential = newRef != null, networkMessage = "Настройки сети применены.") }
                    // Cleanup is deferred until persistence confirms this apply. Failed rotations remain known.
                    val savedRef = library.getNetworkConfig().credentialRef
                    if (savedRef == newRef) {
                        val activeRef = network.context.value.config.credentialRef
                        credentialRefsForCleanup.toList().filter { it != savedRef && it != activeRef }.forEach { obsolete ->
                            secrets.remove(obsolete)
                            credentialRefsForCleanup.remove(obsolete)
                        }
                    }
                }
                is AppResult.Failure -> stateMutable.update { it.copy(networkError = result.error, networkHasCredential = credentialRef != null) }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: AppFailureException) { stateMutable.update { it.copy(networkError = e.error) } }
        catch (_: Exception) { stateMutable.update { it.copy(networkError = AppError(ErrorCode.NETWORK, "settings.apply", true)) } }
        finally { password?.fill('\u0000'); if (ownsApply) stateMutable.update { it.copy(networkApplying = false) } }
    }

    private fun validHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if (':' in host) return host.count { it == ':' } >= 2 && host.matches(Regex("[0-9a-fA-F:]+"))
        return host.split('.').all { label -> label.isNotEmpty() && label.length <= 63 &&
            label.first().isLetterOrDigit() && label.last().isLetterOrDigit() && label.all { it.isLetterOrDigit() || it == '-' } }
    }

    fun retryCommand() {
        if (closed) return
        val retry = failedCommandRetry ?: return
        failedCommandRetry = null
        stateMutable.update { it.copy(commandError = null) }
        retry()
    }

    fun networkInvalidated() {
        ++searchGeneration
        ++selectionGeneration
        searchJob?.cancel()
        selectionJob?.cancel()
        lyricsJob?.cancel()
        awaitingSelection = false
        stateMutable.update { it.copy(loadingSearch = false, loadingLyrics = false) }
    }

    fun close() {
        if (closed) return
        closed = true
        failedCommandRetry = null
        failedRepositorySources.clear()
        ++commandGeneration
        ++searchGeneration
        ++selectionGeneration
        scope.cancel()
    }
}
