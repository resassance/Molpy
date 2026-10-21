package app.musicplayer.playback

internal data class PlayerProgress(
    val positionMs: Long,
    val durationMs: Long?,
    val bufferedPositionMs: Long?,
)

internal fun samplePlayerProgress(
    positionMs: Long,
    durationMs: Long,
    bufferedPositionMs: Long,
): PlayerProgress {
    val duration = durationMs.takeIf { it > 0 }
    return PlayerProgress(
        positionMs.coerceAtLeast(0).let { if (duration != null) it.coerceAtMost(duration) else it },
        duration,
        bufferedPositionMs
            .takeIf { it >= 0 }
            ?.let { if (duration != null) it.coerceAtMost(duration) else it },
    )
}
