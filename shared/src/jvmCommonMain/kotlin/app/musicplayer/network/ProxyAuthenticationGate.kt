package app.musicplayer.network

import app.musicplayer.model.*

/** A helper may answer one proxy challenge in its own active generation, never origin auth. */
class ProxyAuthenticationGate(private val applied: NetworkContext) {
    private var attempted = false
    @Synchronized fun allow(current: NetworkContext, challengeHost: String): Boolean {
        val endpoint = applied.config.endpoint ?: return false
        if (attempted || !current.ready || current.generation != applied.generation ||
            current.config != applied.config || applied.config.mode != NetworkMode.HTTP_CONNECT ||
            applied.config.credentialRef == null ||
            !(challengeHost.equals(endpoint.host, true) || challengeHost.equals("${endpoint.host}:${endpoint.port}", true))) return false
        attempted = true
        return true
    }
}
