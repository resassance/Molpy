package app.musicplayer.playback

import app.musicplayer.model.*
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import io.ktor.client.HttpClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

object ExtractionFactory {
    /**
     * The host owns the configured HTTP client and optional token provider. A fresh anonymous
     * InnerTube session has no Google cookies, account discovery, or optional remote config store.
     * Construction performs no requests or prewarming. The host supplies a generation-routed client.
     */
    fun create(
        httpClient: HttpClient,
        tokenProvider: TokenProvider? = null,
        nowMs: () -> Long,
        currentNetworkGeneration: () -> Long,
        diagnostics: (ResolverDiagnostic) -> Unit = {},
    ): ExtractionSession {
        val innerTube = InnerTube(httpClient)
        val cipher = YouTubeCipherService(httpClient)
        val parser = YtConfigParserImpl(httpClient, innerTube, cipherService = cipher)
        val extractor = InnerTubeExtractor(parser, cipher, innerTube, tokenProvider = tokenProvider)
        return ExtractionSession(
            InnerTubePlaybackResolver(extractor, nowMs, currentNetworkGeneration, diagnostics), innerTube, cipher,
        )
    }
}

/** Owns its anonymous library session and native cipher resources; it borrows HTTP/token services. */
class ExtractionSession internal constructor(
    private val delegate: PlaybackResolver,
    private val innerTube: InnerTube,
    private val cipher: YouTubeCipherService,
) {
    private val closed = AtomicBoolean(false)
    val resolver: PlaybackResolver = object : PlaybackResolver {
        override suspend fun resolve(candidate: PlaybackCandidate, capabilities: PlaybackCapabilities, network: NetworkContext): AppResult<ResolvedPlayback> =
            if (closed.get()) AppResult.Failure(AppError(ErrorCode.SOURCE_UNAVAILABLE, "playback.resolve", false))
            else delegate.resolve(candidate, capabilities, network)
    }

    suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            innerTube.close()
            withContext(NonCancellable) { cipher.dispose() }
        }
    }
}
