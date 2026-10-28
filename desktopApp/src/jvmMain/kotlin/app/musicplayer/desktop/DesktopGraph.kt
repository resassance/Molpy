package app.musicplayer.desktop

import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.catalog.*
import app.musicplayer.desktop.playback.*
import app.musicplayer.model.*
import app.musicplayer.network.configureProviders
import app.musicplayer.playback.*
import app.musicplayer.search.*
import app.musicplayer.storage.*
import app.musicplayer.ui.search.SearchModel
import coil3.PlatformContext
import com.metrolist.innertubex.InnerTube
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*

internal class DesktopGraph(ownerScope: CoroutineScope, dataPath: Path = dataDirectory()) {
    private val database = createMusicDatabase(dataPath.resolve("molpy.db").toString())
    private val job = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + job)
    private val client = HttpClient(OkHttp) { configureProviders() }
    private val catalogSession = InnerTube(client)
    private val network =
        NetworkContext(1, NetworkConfig(NetworkMode.SYSTEM, null, null), true, true)
    private val extraction =
        ExtractionFactory.create(
            client,
            nowMs = System::currentTimeMillis,
            currentNetworkGeneration = { network.generation },
        )
    private val library = RoomLibraryRepository(database, System::currentTimeMillis)
    private val engine = MpvPlayerEngine(scope) { MpvProcess.connect(scope) }
    private val coordinator =
        PlaybackCoordinator(
            engine,
            extraction.resolver,
            { network },
            scope,
            clock = System::currentTimeMillis,
        )
    val player = DesktopPlaybackPort(engine, coordinator, scope)
    val search =
        SearchModel(
                MusicSearchCoordinator(
                    InnerTubeMusicSearch(catalogSession, 1, library),
                    SearchCoordinator(InnerTubeCatalog(catalogSession, library)),
                ),
                player,
                scope,
            )
            .apply { selectFilter(SearchFilter.TRACKS) }
    val artwork = ArtworkLoader(PlatformContext.INSTANCE, client)
    private var closed = false

    suspend fun close() {
        if (closed) return
        closed = true
        search.close()
        coordinator.release()
        job.cancelAndJoin()
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { extraction.close() }
            runCatching { catalogSession.close() }
            artwork.imageLoader.shutdown()
            client.close()
            database.close()
        }
    }
}

private fun dataDirectory(): Path {
    val home = Path.of(System.getProperty("user.home"))
    val directory =
        System.getenv("MOLPY_DATA_DIR")?.let(Path::of)
            ?: if (System.getProperty("os.name").startsWith("Windows", true))
                Path.of(System.getenv("LOCALAPPDATA") ?: home.resolve("AppData/Local").toString())
                    .resolve("Molpy")
            else
                Path.of(System.getenv("XDG_DATA_HOME") ?: home.resolve(".local/share").toString())
                    .resolve("molpy")
    return Files.createDirectories(directory)
}
