package app.musicplayer.model

enum class NetworkMode { SYSTEM, HTTP_CONNECT, SOCKS5 }

data class ProxyEndpoint(val host: String, val port: Int) {
    init {
        require(host.isNotBlank()) { "Proxy host must not be blank" }
        require(port in 1..65535) { "Proxy port is out of range" }
    }
}

data class NetworkConfig(val mode: NetworkMode, val endpoint: ProxyEndpoint?, val credentialRef: SecretId?) {
    init {
        when (mode) {
            NetworkMode.SYSTEM -> require(endpoint == null && credentialRef == null) {
                "System mode does not accept a proxy endpoint or credentials"
            }
            NetworkMode.HTTP_CONNECT -> require(endpoint != null) { "HTTP CONNECT requires an endpoint" }
            NetworkMode.SOCKS5 -> {
                require(endpoint != null) { "SOCKS5 requires an endpoint" }
                require(credentialRef == null) { "Authenticated SOCKS5 is unsupported" }
            }
        }
    }
}

/** Ready means configuration applied; online is platform connectivity, not provider reachability. */
data class NetworkContext(val generation: Long, val config: NetworkConfig, val ready: Boolean, val online: Boolean)
