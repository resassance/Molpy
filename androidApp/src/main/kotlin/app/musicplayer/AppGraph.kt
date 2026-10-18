package app.musicplayer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.common.util.UnstableApi
import app.musicplayer.model.*
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.catalog.*
import app.musicplayer.lyrics.*
import app.musicplayer.search.SearchCoordinator
import app.musicplayer.ui.TechnicalScreenModel
import com.metrolist.innertubex.InnerTube
import app.musicplayer.playback.*
import app.musicplayer.network.configureProviders
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Manual composition only. Playback resources are created and released by PlaybackService. */
class AppGraph(private val context: Context) {
    internal val playback = MutableStateFlow<PlaybackPort?>(null)

    // Application/session lifetime, SYSTEM network route; no account and no eager requests.
    private val catalogHttp by lazy { HttpClient(OkHttp) { configureProviders() } }
    private val catalogSession by lazy { InnerTube(catalogHttp) }
    private val catalog by lazy { InnerTubeCatalog(catalogSession, SessionIdentityStore()) }
    private val search by lazy { SearchCoordinator(catalog) }
    private val lyrics by lazy { LyricsLoader(listOf(YouTubeLyrics(catalogSession), LrcLibLyrics(catalogHttp)),
        LyricsSelector(), SessionLyricsCache(), System::currentTimeMillis) }
    val artwork by lazy { ArtworkLoader(context, catalogHttp) }

    fun createTechnicalModel(player: PlaybackPort, ownerScope: CoroutineScope) =
        TechnicalScreenModel(search, player, lyrics, ownerScope)

    @UnstableApi
    internal fun createPlayback(scope: CoroutineScope): PlaybackRuntime {
        val http = HttpClient(OkHttp) { configureProviders() }
        val extraction = ExtractionFactory.create(http, AndroidTokenProvider(), System::currentTimeMillis, { 0L })
        val engine = AndroidPlayerEngine(context)
        val coordinator = PlaybackCoordinator(engine, extraction.resolver, ::networkContext, scope)
        return PlaybackRuntime(engine, coordinator, extraction, http)
    }
    private fun networkContext(): NetworkContext {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val online = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        return NetworkContext(0, NetworkConfig(NetworkMode.SYSTEM, null, null), true, online)
    }
}

@UnstableApi
internal class PlaybackRuntime(
    val engine: AndroidPlayerEngine,
    val coordinator: PlaybackCoordinator,
    private val extraction: ExtractionSession,
    private val http: HttpClient,
) {
    suspend fun closeExtraction() { try { extraction.close() } finally { http.close() } }
}
