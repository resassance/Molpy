package app.musicplayer.downloads

import app.musicplayer.model.AppError
import app.musicplayer.model.AppResult
import app.musicplayer.model.ErrorCode

data class CompletionEvidence(
    val expectedBytes: Long?,
    val storedBytes: Long,
    val hasHoles: Boolean,
    val sourceDurationMs: Long?,
    val decodedDurationMs: Long?,
    val readable: Boolean,
    val transportComplete: Boolean,
)

/** Verifies evidence only; the platform must collect it before publishing AVAILABLE. */
class CompletionPolicy {
    fun verify(evidence: CompletionEvidence): AppResult<Unit> {
        if (!evidence.transportComplete || !evidence.readable || evidence.hasHoles || evidence.storedBytes <= 0 ||
            evidence.expectedBytes?.let { it <= 0 || it != evidence.storedBytes } == true ||
            evidence.sourceDurationMs?.let { it <= 0 } == true || evidence.decodedDurationMs?.let { it <= 0 } == true
        ) return incomplete()

        val sourceDuration = evidence.sourceDurationMs
        val decodedDuration = evidence.decodedDurationMs
        if (sourceDuration != null && decodedDuration != null) {
            // Positive values make the subtraction safe; division avoids overflow and fractional rounding up.
            val difference = if (sourceDuration >= decodedDuration) sourceDuration - decodedDuration else decodedDuration - sourceDuration
            val tolerance = maxOf(2_000L, sourceDuration / 50)
            if (difference > tolerance) return incomplete()
        } else if (evidence.expectedBytes == null) {
            return incomplete()
        }
        return AppResult.Success(Unit)
    }

    private fun incomplete(): AppResult<Unit> =
        AppResult.Failure(AppError(ErrorCode.INCOMPLETE_MEDIA, "downloads.verify", true))
}
