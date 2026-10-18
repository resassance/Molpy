package app.musicplayer.network

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Shared configuration used by the application's catalog/lyrics and service extraction clients. */
fun HttpClientConfig<*>.configureProviders() {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 30_000
        socketTimeoutMillis = 30_000
    }
}
