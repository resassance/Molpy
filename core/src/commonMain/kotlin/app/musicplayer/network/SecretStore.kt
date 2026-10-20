package app.musicplayer.network

import app.musicplayer.model.SecretId

/** Runtime-only credentials. Never include this object in settings, diagnostics, or Room. */
class ProxyCredentials(val username: String, password: CharArray) : AutoCloseable {
    private val secret = password.copyOf()
    fun password(): CharArray = secret.copyOf()
    override fun close() { secret.fill('\u0000') }
    override fun toString(): String = "ProxyCredentials([redacted])"
}

interface SecretStore {
    suspend fun put(username: String, password: CharArray): SecretId
    suspend fun read(id: SecretId): ProxyCredentials?
    suspend fun remove(id: SecretId)
}
