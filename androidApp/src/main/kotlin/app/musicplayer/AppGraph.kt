package app.musicplayer

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.artwork.samplePixels
import app.musicplayer.catalog.*
import app.musicplayer.diagnostics.DiagnosticEvent
import app.musicplayer.diagnostics.RedactedDiagnostics
import app.musicplayer.downloads.*
import app.musicplayer.library.LibraryRepository
import app.musicplayer.listening.*
import app.musicplayer.lyrics.*
import app.musicplayer.model.*
import app.musicplayer.network.*
import app.musicplayer.playback.*
import app.musicplayer.queue.*
import app.musicplayer.search.SearchCoordinator
import app.musicplayer.storage.RoomCollectionsRepository
import app.musicplayer.storage.RoomLibraryRepository
import app.musicplayer.storage.RoomListeningRepository
import app.musicplayer.storage.createMusicDatabase
import app.musicplayer.ui.MolpyModels
import app.musicplayer.ui.catalog.CatalogModel
import app.musicplayer.ui.components.TrackActions
import app.musicplayer.ui.downloads.DownloadsModel
import app.musicplayer.ui.library.LibraryModel
import app.musicplayer.ui.player.PlayerModel
import app.musicplayer.ui.profile.ProfileModel
import app.musicplayer.ui.search.SearchModel
import app.musicplayer.ui.settings.NetworkSettingsModel
import app.musicplayer.ui.theme.PaletteModel
import com.metrolist.innertubex.InnerTube
import java.lang.ref.WeakReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock

/** Manual composition. The library repository injects its single saved network profile boundary. */
class AppGraph(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val playback = MutableStateFlow<QueuePlaybackPort?>(null)
    private val database by lazy { createMusicDatabase(context) }
    val library by lazy { RoomLibraryRepository(database, System::currentTimeMillis) }
    val collections by lazy { RoomCollectionsRepository(database, System::currentTimeMillis) }
    val listening by lazy { RoomListeningRepository(database, System::currentTimeMillis) }
    private val downloadRuntime by lazy { createDownloadRuntime() }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private fun createDownloadRuntime(): app.musicplayer.downloads.DownloadRuntime =
        app.musicplayer.downloads.DownloadRuntime(
            context,
            library,
            network,
            onAssetsChanged = { offlineResources.signal() },
        )

    @get:UnstableApi
    internal val downloadStore
        get() = downloadRuntime.store

    @get:UnstableApi
    val downloads: app.musicplayer.downloads.DownloadPort
        get() = downloadRuntime.port

    val diagnostics = RedactedDiagnostics()
    val secrets: SecretStore = AndroidSecretStore(context)
    val network =
        AndroidNetworkController(
            context,
            secrets,
            loadConfig = { library.getNetworkConfig() },
            saveConfig = { library.saveNetworkConfig(it) },
        )
    private val models = mutableListOf<WeakReference<MolpyModels>>()
    private var catalogSession: InnerTube? = null
    private var activeCatalog: CatalogPort? = null
    private var activeBrowse: BrowseCatalog? = null
    val browse: BrowseCatalog = RoutedBrowseCatalog({ activeBrowse }, { network.context.value })
    private var activeLyrics: LyricsPort? = null
    private val remoteCatalog =
        object : CatalogPort {
            override suspend fun searchSongs(query: String) = catalogOperation {
                it.searchSongs(query)
            }

            override suspend fun searchVideos(query: String) = catalogOperation {
                it.searchVideos(query)
            }

            override suspend fun findMusicMatches(track: Track) = catalogOperation {
                it.findMusicMatches(track)
            }
        }
    private val catalog = CachedCatalog(remoteCatalog, library, System::currentTimeMillis)
    private val search = SearchCoordinator(catalog)
    private val lyrics =
        LyricsLoader(
            listOf(
                object : LyricsPort {
                    override suspend fun fetch(
                        track: Track,
                        candidate: PlaybackCandidate,
                    ): AppResult<LyricsCandidate?> {
                        val provider = activeLyrics
                        return if (!network.context.value.ready || provider == null)
                            unavailable("lyrics.route")
                        else provider.fetch(track, candidate)
                    }
                },
                LrcLibLyrics(network.transport.http),
            ),
            LyricsSelector(),
            library,
            System::currentTimeMillis,
        )
    private val artworkDelegate = lazy { ArtworkLoader(context, network.transport.http) }
    val artwork by artworkDelegate
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private val offlineResourcesDelegate = lazy {
        OfflineResources(
            java.io.File(context.noBackupFilesDir, "downloads/resources"),
            library,
            library,
            artwork,
            lyrics,
            scope,
            { network.context.value.ready && network.context.value.online },
            { action -> downloadStore.mutex.withLock { action() } },
            retainedArtworkKeys = library::retainedArtworkKeys,
        )
    }
    @get:UnstableApi
    private val offlineResources
        get() = offlineResourcesDelegate.value

    @get:UnstableApi
    private val storageUsage
        get() = StorageUsage(downloadStore.cache, library, offlineResources)

    init {
        network.transport.addListener(
            object : NetworkLifecycle {
                override suspend fun invalidated(context: NetworkContext) {
                    withContext(Dispatchers.Main.immediate) {
                        models.removeAll { it.get() == null }
                        models.forEach { it.get()?.networkInvalidated() }
                        if (artworkDelegate.isInitialized()) artwork.networkInvalidated()
                        activeCatalog = null
                        activeBrowse = null
                        activeLyrics = null
                        catalogSession?.close()
                        catalogSession = null
                    }
                }

                override suspend fun activated(context: NetworkContext) {
                    withContext(Dispatchers.Main.immediate) {
                        val session = InnerTube(network.transport.http)
                        catalogSession = session
                        activeCatalog = InnerTubeCatalog(session, library)
                        activeBrowse = InnerTubeBrowseCatalog(session, context.generation, library)
                        activeLyrics = YouTubeLyrics(session)
                        models.removeAll { it.get() == null }
                        models.forEach { it.get()?.networkActivated() }
                        if (offlineResourcesDelegate.isInitialized())
                            offlineResourcesDelegate.value.signal()
                    }
                }
            }
        )
        // Initial state stays unready while the repository loads and WebView applies its native
        // route.
        scope.launch { network.initialize() }
    }

    private suspend fun catalogOperation(
        action: suspend (CatalogPort) -> AppResult<List<Track>>
    ): AppResult<List<Track>> {
        val provider = activeCatalog
        if (!network.context.value.ready || provider == null) return unavailable("catalog.route")
        val generation = network.context.value.generation
        val result = action(provider)
        if (!network.context.value.ready || generation != network.context.value.generation)
            throw CancellationException("Network generation changed")
        return result
    }

    private fun unavailable(operation: String) =
        AppResult.Failure(AppError(ErrorCode.NETWORK, operation, true))

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun createModels(player: QueuePlaybackPort, ownerScope: CoroutineScope): MolpyModels =
        MolpyModels(
                PlayerModel(player, library, lyrics, ownerScope),
                SearchModel(search, player, ownerScope),
                LibraryModel(collections, player, ownerScope),
                CatalogModel(browse, collections, player, ownerScope),
                ProfileModel(listening, player, ownerScope),
                DownloadsModel(downloads, { storageUsage.measure() }, ownerScope),
                NetworkSettingsModel(network, library, secrets, ownerScope),
                PaletteModel({ artwork.samplePixels(context, it) }, ownerScope),
                TrackActions(library, collections, player, downloads),
            )
            .also { models.add(WeakReference(it)) }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    internal fun createPlayback(scope: CoroutineScope): PlaybackRuntime =
        PlaybackRuntime(context, network, scope, library, diagnostics, downloadStore, listening)
}

/**
 * Service lifetime. Every generation gets a fresh anonymous extractor/cipher and no retained URL.
 */
@UnstableApi
internal class PlaybackRuntime(
    context: Context,
    private val network: AndroidNetworkController,
    private val scope: CoroutineScope,
    library: LibraryRepository,
    diagnostics: RedactedDiagnostics,
    private val downloads: DownloadStore,
    listeningRepository: ListeningRepository,
) {
    private val local = OfflineMediaSource(downloads.cache)
    val engine =
        AndroidPlayerEngine(context, network.transport::callFactoryFor, local.dataSourceFactory)
    private var extraction: ExtractionSession? =
        if (network.context.value.ready) newExtraction() else null
    private var reconnect: Job? = null
    @Volatile private var closed = false
    private val resolver =
        object : PlaybackResolver {
            override suspend fun resolve(
                candidate: PlaybackCandidate,
                capabilities: PlaybackCapabilities,
                network: NetworkContext,
            ): AppResult<ResolvedPlayback> =
                extraction?.resolver?.resolve(candidate, capabilities, network)
                    ?: AppResult.Failure(AppError(ErrorCode.NETWORK, "playback.route", true))
        }
    val coordinator =
        PlaybackCoordinator(
            engine,
            resolver,
            { network.context.value },
            scope,
            diagnostics::record,
            "innertubex-0.10.0",
            SystemClock::elapsedRealtime,
            PlaybackSourceSelector(
                resolver,
                { network.context.value },
                downloads.repository,
                local::select,
                { downloads.startup.await() },
                { id ->
                    downloads.mutex.withLock {
                        val asset = downloads.repository.findAsset(id)
                        if (asset?.state == DownloadState.AVAILABLE) {
                            downloads.repository.saveMapping(
                                asset.copy(state = DownloadState.FAILED)
                            )
                            downloads.fail(
                                id,
                                AppError(ErrorCode.INCOMPLETE_MEDIA, "playback.local", false),
                            )
                            downloads.refresh()
                        }
                    }
                },
            ),
        )
    val queue = QueueController(engine, coordinator, library::getTrack)
    val persistence =
        PlaybackPersistence(
            library,
            queue,
            coordinator,
            scope,
            SystemClock::elapsedRealtime,
            System::currentTimeMillis,
            diagnostics::record,
        )
    val listening =
        ListeningTracker(
            listeningRepository,
            queue.snapshot,
            scope,
            SystemClock::elapsedRealtime,
            System::currentTimeMillis,
        )
    val startup = scope.async { persistence.initialize() }
    private val listener =
        object : NetworkLifecycle {
            override suspend fun invalidated(context: NetworkContext) {
                val previous =
                    withContext(Dispatchers.Main.immediate) {
                        reconnect?.cancel()
                        reconnect = null
                        if (!closed && scope.isActive) coordinator.networkInvalidated()
                        extraction.also { extraction = null }
                    }
                previous?.close()
            }

            override suspend fun activated(context: NetworkContext) {
                withContext(Dispatchers.Main.immediate) {
                    if (closed || !scope.isActive) return@withContext
                    extraction = newExtraction()
                    reconnect = scope.launch { coordinator.networkActivated() }
                }
            }
        }

    init {
        network.transport.addListener(listener)
        scope.launch {
            listening.error.filterNotNull().collect { error ->
                diagnostics.record(
                    DiagnosticEvent("listening", error.operationId, null, null, null, error.code, 0)
                )
            }
        }
    }

    private fun newExtraction() =
        ExtractionFactory.create(
            network.transport.http,
            AndroidTokenProvider { network.context.value },
            System::currentTimeMillis,
            { network.context.value.generation },
        )

    suspend fun closeExtraction() {
        closed = true
        network.transport.removeListener(listener)
        val previous =
            withContext(Dispatchers.Main.immediate) {
                reconnect?.cancel()
                reconnect = null
                extraction.also { extraction = null }
            }
        previous?.close()
    }
}
