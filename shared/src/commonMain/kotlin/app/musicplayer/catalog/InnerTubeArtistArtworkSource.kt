package app.musicplayer.catalog

import app.musicplayer.artists.*
import app.musicplayer.model.*
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeHttpException
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.plugins.*
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

class InnerTubeArtistArtworkSource(private val session: InnerTube) : ArtistArtworkSource {
    override suspend fun fetch(source: SourceRef): AppResult<Artwork?> {
        if (!validArtistArtworkSource(source)) return failure(ErrorCode.SOURCE_UNAVAILABLE)
        return try {
            val response =
                session.browse(
                    YouTubeClient.WEB_REMIX,
                    source.id,
                    setLogin = false,
                )
            if (response.status.value !in 200..299) httpFailure(response.status.value)
            else ArtistArtworkMapper.map(response.bodyAsText(), source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InnerTubeHttpException) {
            httpFailure(e.status.value)
        } catch (e: ResponseException) {
            httpFailure(e.response.status.value)
        } catch (_: HttpRequestTimeoutException) {
            failure(ErrorCode.NETWORK, true)
        } catch (_: IOException) {
            failure(ErrorCode.NETWORK, true)
        } catch (_: Exception) {
            failure(ErrorCode.UNKNOWN)
        }
    }

    private fun httpFailure(status: Int) =
        when (status) {
            401 -> failure(ErrorCode.LOGIN_REQUIRED)
            403,
            404,
            410 -> failure(ErrorCode.SOURCE_UNAVAILABLE)
            407 -> failure(ErrorCode.PROXY_AUTH)
            429 -> failure(ErrorCode.RATE_LIMIT, true)
            408,
            425,
            in 500..599 -> failure(ErrorCode.NETWORK, true)
            else -> failure(ErrorCode.PROVIDER_CHANGED)
        }

    private fun failure(code: ErrorCode, retryable: Boolean = false) =
        AppResult.Failure(AppError(code, "catalog.artist.artwork", retryable))
}
