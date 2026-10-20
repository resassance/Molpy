package app.musicplayer.network

import app.musicplayer.model.*

object NetworkConfigValidator {
    /** Settings boundary validates raw input before constructing invariant-enforcing core values. */
    fun validate(mode: NetworkMode, host: String?, port: Int?, credentialRef: SecretId?): AppResult<NetworkConfig> {
        if (mode == NetworkMode.SOCKS5 && credentialRef != null) return failure(ErrorCode.PROXY_UNSUPPORTED)
        if (mode == NetworkMode.SYSTEM) return if (host == null && port == null && credentialRef == null)
            AppResult.Success(NetworkConfig(mode, null, null)) else failure(ErrorCode.INVALID_CONFIG)
        if (host.isNullOrBlank() || host != host.trim() || host.length > 253 ||
            host.any { it.isWhitespace() || it in "/@?#\\" } || "://" in host ||
            (':' in host && !host.matches(Regex("[0-9a-fA-F:]+"))) || port == null || port !in 1..65535) return failure(ErrorCode.INVALID_CONFIG)
        return AppResult.Success(NetworkConfig(mode, ProxyEndpoint(host, port), credentialRef))
    }
    fun validate(config: NetworkConfig): AppResult<NetworkConfig> = validate(config.mode, config.endpoint?.host, config.endpoint?.port, config.credentialRef)
    private fun failure(code: ErrorCode) = AppResult.Failure(AppError(code, "network.config", false))
}
