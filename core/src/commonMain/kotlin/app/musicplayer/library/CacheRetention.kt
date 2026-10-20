package app.musicplayer.library

object CacheRetention {
    const val RECENT_LIMIT = 100
    const val METADATA_LIMIT = 1000
    const val METADATA_TTL_MS = 7L * 24 * 60 * 60 * 1000
    const val SEARCH_TTL_MS = 15L * 60 * 1000
    const val UNAVAILABLE_LYRICS_TTL_MS = 24L * 60 * 60 * 1000

    fun isFresh(atMs: Long, nowMs: Long, ttlMs: Long): Boolean =
        nowMs >= atMs && nowMs - atMs in 0 until ttlMs
}
