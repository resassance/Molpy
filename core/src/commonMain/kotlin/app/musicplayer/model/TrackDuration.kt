package app.musicplayer.model

fun Track.withMissingDuration(source: SourceRef, durationMs: Long): Track {
    if (durationMs <= 0 || candidates.none { it.source == source }) return this
    return copy(
        durationMs =
            this.durationMs?.takeIf { it > 0 }
                ?: durationMs.takeIf { candidates.firstOrNull()?.source == source },
        candidates =
            candidates.map {
                if (it.source == source && (it.durationMs == null || it.durationMs <= 0))
                    it.copy(durationMs = durationMs)
                else it
            },
    )
}
