package app.musicplayer.artwork

import android.content.Context
import android.graphics.Bitmap
import app.musicplayer.model.*
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.*

suspend fun ArtworkLoader.samplePixels(context: Context, artwork: Artwork): AppResult<IntArray> {
    val data =
        artwork.localKey
            ?: artwork.remoteUrl
            ?: return AppResult.Failure(
                AppError(ErrorCode.SOURCE_UNAVAILABLE, "artwork.palette", false)
            )
    return try {
        val result =
            imageLoader.execute(
                ImageRequest.Builder(context).data(data).size(32, 32).allowHardware(false).build()
            )
        if (result !is SuccessResult)
            return AppResult.Failure(
                AppError(ErrorCode.SOURCE_UNAVAILABLE, "artwork.palette", true)
            )
        withContext(Dispatchers.Default) {
            // The original bitmap belongs to Coil's cache and must not be recycled here.
            val bitmap = Bitmap.createScaledBitmap(result.image.toBitmap(), 32, 32, true)
            val pixels = IntArray(1024)
            bitmap.getPixels(pixels, 0, 32, 0, 0, 32, 32)
            AppResult.Success(pixels)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        AppResult.Failure(AppError(ErrorCode.UNKNOWN, "artwork.palette", true))
    }
}
