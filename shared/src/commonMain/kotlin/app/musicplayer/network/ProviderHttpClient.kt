package app.musicplayer.network

import io.ktor.client.HttpClientConfig
import app.musicplayer.model.AppFailureException
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Shared configuration used by the application's catalog/lyrics and service extraction clients. */
fun HttpClientConfig<*>.configureProviders() {
    // OkHttp requires IOExceptions on async failures. Recover only our safe typed cause.
    HttpResponseValidator {
        handleResponseExceptionWithRequest { failure, _ ->
            var cause: Throwable? = failure
            repeat(8) {
                if (cause is AppFailureException) throw cause
                cause = cause?.cause
            }
        }
    }
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 30_000
        socketTimeoutMillis = 30_000
    }
}
