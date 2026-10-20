package app.musicplayer.artwork

import app.musicplayer.model.*
import coil3.ColorImage
import coil3.Image
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

data class ArtworkBytes(val bytes: ByteArray, val mimeType: String)

/** Both Coil display and explicit offline-byte preparation use the caller's network policy/client. */
class ArtworkLoader(
    context: PlatformContext,
    private val client: HttpClient,
    placeholder: Image = ColorImage(0xff30343b.toInt()),
) {
    @OptIn(coil3.annotation.ExperimentalCoilApi::class)
    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .components { add(KtorNetworkFetcherFactory(httpClient = client)) }
        .diskCache(null)
        .placeholder(placeholder)
        .error(placeholder)
        .fallback(placeholder)
        .build()

    fun networkInvalidated() { imageLoader.memoryCache?.clear() }

    suspend fun fetch(artwork: Artwork): AppResult<ArtworkBytes> {
        val address = artwork.remoteUrl?.takeIf { artwork.kind != ArtworkKind.PLACEHOLDER }
            ?: return failure(ErrorCode.SOURCE_UNAVAILABLE)
        val url = try { Url(address) } catch (_: IllegalArgumentException) { return failure(ErrorCode.URL_REJECTED) }
        if (url.protocol != URLProtocol.HTTP && url.protocol != URLProtocol.HTTPS) return failure(ErrorCode.URL_REJECTED)
        return try {
            val response = client.get(url)
            if (!response.status.isSuccess()) return httpFailure(response.status.value)
            val mime = response.contentType()?.withoutParameters()?.toString()?.lowercase()
                ?.takeIf { it.startsWith("image/") } ?: return failure(ErrorCode.PROVIDER_CHANGED)
            val bytes = response.bodyAsBytes()
            if (bytes.isEmpty()) failure(ErrorCode.PROVIDER_CHANGED) else AppResult.Success(ArtworkBytes(bytes, mime))
        } catch (e: CancellationException) { throw e }
        catch (e: AppFailureException) { AppResult.Failure(e.error) }
        catch (e: ResponseException) { httpFailure(e.response.status.value) }
        catch (_: IOException) { failure(ErrorCode.NETWORK, true) }
        catch (_: Exception) { failure(ErrorCode.UNKNOWN) }
    }

    private fun httpFailure(status: Int) = when (status) {
        401 -> failure(ErrorCode.LOGIN_REQUIRED)
        403, 404, 410 -> failure(ErrorCode.SOURCE_UNAVAILABLE)
        407 -> failure(ErrorCode.PROXY_AUTH)
        429 -> failure(ErrorCode.RATE_LIMIT, true)
        408, in 500..599 -> failure(ErrorCode.NETWORK, true)
        else -> failure(ErrorCode.PROVIDER_CHANGED)
    }
    private fun failure(code: ErrorCode, retryable: Boolean = false) =
        AppResult.Failure(AppError(code, "artwork.fetch", retryable))
}
