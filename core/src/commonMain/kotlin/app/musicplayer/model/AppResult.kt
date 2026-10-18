package app.musicplayer.model

/** Boundaries normalize failures explicitly; coroutine cancellation must propagate to the caller. */
sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

fun <T> AppResult<T>.errorOrNull(): AppError? = when (this) {
    is AppResult.Success -> null
    is AppResult.Failure -> error
}

fun <T> AppResult<T>.valueOrNull(): T? = when (this) {
    is AppResult.Success -> value
    is AppResult.Failure -> null
}

data class AppError(val code: ErrorCode, val operationId: String, val retryable: Boolean)

enum class ErrorCode {
    INVALID_CONFIG, NETWORK, PROXY_AUTH, PROXY_UNSUPPORTED, SOURCE_UNAVAILABLE,
    REGION_RESTRICTED, LOGIN_REQUIRED, RATE_LIMIT, PROVIDER_CHANGED, UNSUPPORTED_TRANSPORT,
    URL_REJECTED, STORAGE_FULL, INCOMPLETE_MEDIA, INCOMPATIBLE_RESOURCE, DATABASE, UNKNOWN,
}

/** Safe exception for contracts whose regular return value must distinguish absence from failure. */
class AppFailureException(val error: AppError) : Exception(error.code.name)
