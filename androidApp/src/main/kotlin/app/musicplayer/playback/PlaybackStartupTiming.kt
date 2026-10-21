package app.musicplayer.playback

internal class PlaybackStartupTiming {
    private var loadId: Long? = null
    private var startedAtMs = 0L
    private var recorded = false

    fun start(loadId: Long?, atMs: Long) {
        this.loadId = loadId
        startedAtMs = atMs
        recorded = false
    }

    fun firstAudio(loadId: Long?, atMs: Long): Long? {
        if (loadId == null || this.loadId != loadId || recorded) return null
        recorded = true
        return (atMs - startedAtMs).coerceAtLeast(0)
    }

    fun clear() {
        loadId = null
        recorded = false
    }
}
