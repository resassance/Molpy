package app.musicplayer.playback

import app.musicplayer.model.NetworkContext
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities

/**
 * Truthful host boundary until an anonymous attestation component and its network route are verified.
 * This does not implement WebView minting or advertise a provider that cannot mint tokens.
 */
class AndroidTokenProvider(private val network: () -> NetworkContext) : TokenProvider {
    override val capabilities: TokenProviderCapabilities = TokenProviderCapabilities()
    override suspend fun getPoToken(videoId: String, visitorData: String, cookie: String?): PoTokenResult? {
        if (!network().ready) return null
        return null
    }
}
