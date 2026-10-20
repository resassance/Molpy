package app.musicplayer.lyrics

import app.musicplayer.model.*
import com.metrolist.innertubex.InnerTubeHttpException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.*
import kotlinx.io.IOException

/** Invoked on demand by a lyrics surface or offline preparation, never while mapping catalog rows. */
class LyricsLoader(
    private val providers: List<LyricsPort>,
    private val selector: LyricsSelector,
    private val cache: LyricsCache,
    private val clock: () -> Long,
) {
    suspend fun load(track: Track, candidate: PlaybackCandidate): LyricsLookup {
        val now = clock()
        try {
            cache.getLyrics(track.id, candidate.id, now)?.let { return it }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return LyricsLookup.Failed(AppError(ErrorCode.DATABASE, "lyrics.cache.read", true)) }
        if (providers.isEmpty()) return LyricsLookup.Failed(AppError(ErrorCode.INVALID_CONFIG, "lyrics.providers", false))
        val results = coroutineScope {
            providers.map { provider -> async {
                try {
                    withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { provider.fetch(track, candidate) }
                        ?: AppResult.Failure(AppError(ErrorCode.NETWORK, "lyrics.provider.timeout", true))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lyricsFailure(e, "lyrics.provider.fetch")
                }
            } }.awaitAll()
        }
        val selected = selector.select(results.mapNotNull { it.valueOrNull() })
        val lookup = when {
            selected?.kind == LyricsKind.INSTRUMENTAL -> LyricsLookup.Instrumental(selected.source)
            selected != null -> LyricsLookup.Available(selected)
            results.any { it is AppResult.Failure } -> LyricsLookup.Failed(results.firstNotNullOf { it.errorOrNull() })
            else -> LyricsLookup.Unavailable
        }
        // Transient provider failures must retain their meaning and never enter the cache.
        if (lookup is LyricsLookup.Failed) return lookup
        val savedAt = clock()
        try {
            cache.saveLyrics(track.id, candidate.id, lookup, savedAt)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return LyricsLookup.Failed(AppError(ErrorCode.DATABASE, "lyrics.cache.write", true)) }
        return lookup
    }

    private companion object { const val PROVIDER_TIMEOUT_MS = 6000L }
}

internal fun lyricsHttpFailure(status: Int, operation: String): AppResult.Failure {
    val code = when (status) {
        401 -> ErrorCode.LOGIN_REQUIRED
        403, 404, 410 -> ErrorCode.SOURCE_UNAVAILABLE
        407 -> ErrorCode.PROXY_AUTH
        429 -> ErrorCode.RATE_LIMIT
        408, 425, in 500..599 -> ErrorCode.NETWORK
        else -> ErrorCode.PROVIDER_CHANGED
    }
    return AppResult.Failure(AppError(code, operation, code == ErrorCode.NETWORK || code == ErrorCode.RATE_LIMIT))
}

internal fun lyricsFailure(error: Exception, operation: String): AppResult.Failure = when (error) {
    is CancellationException -> throw error
    is InnerTubeHttpException -> lyricsHttpFailure(error.status.value, operation)
    is ResponseException -> lyricsHttpFailure(error.response.status.value, operation)
    is HttpRequestTimeoutException, is IOException -> AppResult.Failure(AppError(ErrorCode.NETWORK, operation, true))
    is AppFailureException -> AppResult.Failure(error.error)
    else -> AppResult.Failure(AppError(ErrorCode.UNKNOWN, operation, false))
}
