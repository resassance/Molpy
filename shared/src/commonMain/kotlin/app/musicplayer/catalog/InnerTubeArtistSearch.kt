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

class InnerTubeArtistSearch(private val session: InnerTube, private val browse: BrowseCatalog) :
    ArtistSearchPort {
    override suspend fun search(query: String): AppResult<List<ArtistCatalogHit>> {
        if (query.isBlank()) return failure(ErrorCode.INVALID_CONFIG)
        return try {
            val response =
                session.search(
                    YouTubeClient.WEB_REMIX,
                    query.trim(),
                    ARTIST_FILTER,
                    setLogin = false,
                )
            if (response.status.value !in 200..299) httpFailure(response.status.value)
            else ArtistSearchMapper.map(response.bodyAsText())
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

    override suspend fun validate(hit: ArtistCatalogHit): AppResult<ArtistCatalogHit> {
        if (hit.source.provider != "youtube" || hit.source.kind != "artist" || hit.name.isBlank())
            return failure(ErrorCode.SOURCE_UNAVAILABLE)
        return when (val result = browse.artist(ArtistRef(hit.name, hit.source))) {
            is AppResult.Failure -> result
            is AppResult.Success -> {
                val profile = result.value
                if (profile.artist.source != hit.source || profile.artist.name.isBlank())
                    failure(ErrorCode.PROVIDER_CHANGED)
                else
                    AppResult.Success(
                        hit.copy(
                            name = profile.artist.name,
                            artwork = profile.artwork ?: hit.artwork,
                        )
                    )
            }
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
        AppResult.Failure(AppError(code, "catalog.artist.search", retryable))

    private companion object {
        // Music artist filter verified against the maintained Metrolist search implementation.
        const val ARTIST_FILTER = "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"
    }
}
