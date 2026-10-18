package app.musicplayer.catalog

import app.musicplayer.model.*
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeHttpException
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import kotlinx.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Anonymous catalog operations. The caller owns the InnerTube instance and its configured HttpClient
 * (including request serialization, transport, proxy and TLS policy).
 */
class InnerTubeCatalog(private val client: InnerTube, identities: IdentityStore) : CatalogPort {
    private val mapper = CatalogMapper(identities)

    override suspend fun searchSongs(query: String): AppResult<List<Track>> = search(query, music = true)

    override suspend fun searchVideos(query: String): AppResult<List<Track>> = search(query, music = false)

    /** Candidate retrieval only; matching and any identity linkage belong to the application layer. */
    override suspend fun findMusicMatches(track: Track): AppResult<List<Track>> =
        searchSongs((listOf(track.title) + track.artists.map { it.name }).joinToString(" ").trim())

    private suspend fun search(query: String, music: Boolean): AppResult<List<Track>> {
        val operation = if (music) "catalog.music.search" else "catalog.video.search"
        if (query.isBlank()) return failure(ErrorCode.INVALID_CONFIG, operation)
        return try {
            val response = client.search(
                client = if (music) YouTubeClient.WEB_REMIX else YouTubeClient.WEB,
                query = query,
                params = if (music) SONG_FILTER else VIDEO_FILTER,
                setLogin = false,
            )
            val body = response.bodyAsText()
            if (music) mapper.mapSongs(body) else mapper.mapVideos(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InnerTubeHttpException) {
            httpFailure(e.status.value, operation)
        } catch (e: ResponseException) {
            httpFailure(e.response.status.value, operation)
        } catch (_: HttpRequestTimeoutException) {
            failure(ErrorCode.NETWORK, operation, retryable = true)
        } catch (_: IOException) {
            failure(ErrorCode.NETWORK, operation, retryable = true)
        } catch (e: AppFailureException) {
            AppResult.Failure(e.error)
        } catch (_: Exception) {
            failure(ErrorCode.UNKNOWN, operation)
        }
    }

    private fun httpFailure(status: Int, operation: String): AppResult.Failure = when (status) {
        401 -> failure(ErrorCode.LOGIN_REQUIRED, operation)
        403, 404, 410 -> failure(ErrorCode.SOURCE_UNAVAILABLE, operation)
        407 -> failure(ErrorCode.PROXY_AUTH, operation)
        429 -> failure(ErrorCode.RATE_LIMIT, operation, retryable = true)
        408, 425, in 500..599 -> failure(ErrorCode.NETWORK, operation, retryable = true)
        else -> failure(ErrorCode.PROVIDER_CHANGED, operation)
    }

    private fun failure(code: ErrorCode, operation: String, retryable: Boolean = false) =
        AppResult.Failure(AppError(code, operation, retryable))

    private companion object {
        // InnerTube search protocol filters, passed verbatim in the tagged SearchBody.params field.
        const val SONG_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        const val VIDEO_FILTER = "EgIQAQ%3D%3D"
    }
}
