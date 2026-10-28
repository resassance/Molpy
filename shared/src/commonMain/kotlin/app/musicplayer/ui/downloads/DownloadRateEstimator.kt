package app.musicplayer.ui.downloads

import app.musicplayer.downloads.*
import kotlin.math.ceil

data class DownloadRate(
    val bytesPerSecond: Long? = null,
    val remainingSeconds: Long? = null,
    val waitingForData: Boolean = false,
)

internal class DownloadRateEstimator {
    private data class Sample(val bytes: Long, val time: Long)

    private val samples = ArrayDeque<Sample>()
    private var attempt: String? = null
    private var lastGrowth = 0L
    private var stalled = false

    fun update(activity: DownloadActivity?, nowMs: Long): DownloadRate {
        if (activity?.phase != DownloadPhase.DOWNLOADING) {
            samples.clear()
            attempt = null
            stalled = false
            return DownloadRate()
        }
        val previous = samples.lastOrNull()
        if (
            attempt != activity.attempt ||
                previous == null ||
                activity.bytes < previous.bytes ||
                nowMs < previous.time
        ) {
            samples.clear()
            attempt = activity.attempt
            lastGrowth = nowMs
            stalled = false
        } else if (activity.bytes > previous.bytes) {
            if (stalled) samples.clear()
            stalled = false
            lastGrowth = nowMs
        } else if (nowMs - lastGrowth >= 5000) {
            stalled = true
            return DownloadRate(waitingForData = true)
        }
        samples.addLast(Sample(activity.bytes, nowMs))
        while (samples.size > 2 && samples[1].time <= nowMs - 5000) samples.removeFirst()
        val start = samples.first()
        val elapsed = nowMs - start.time
        val delta = activity.bytes - start.bytes
        if (elapsed < 1000 || delta <= 0) return DownloadRate()
        val speed =
            (delta.toDouble() * 1000 / elapsed).toLong().takeIf { it > 0 } ?: return DownloadRate()
        val remaining =
            activity.totalBytes
                ?.takeIf { it > 0 }
                ?.let {
                    ceil((it - activity.bytes).coerceAtLeast(0).toDouble() / speed).toLong()
                }
        return DownloadRate(speed, remaining)
    }
}
