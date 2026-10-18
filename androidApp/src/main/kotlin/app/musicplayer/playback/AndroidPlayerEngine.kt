package app.musicplayer.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient

/** The service owns this engine. All Media3 calls and listeners run on Android's main looper. */
@UnstableApi
class AndroidPlayerEngine(context: Context) : PlayerEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http = OkHttpClient()
    private val sources = MediaSourceFactory(http)
    internal val player: ExoPlayer
    private val state = MutableStateFlow(PlaybackSnapshot())
    override val snapshot: StateFlow<PlaybackSnapshot> = state.asStateFlow()
    private var entry: QueueEntry? = null
    private var track: Track? = null
    private var released = false
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { publish() }
    }

    init {
        checkMain()
        player = ExoPlayer.Builder(context.applicationContext)
            .setLooper(Looper.getMainLooper())
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(listener)
        // Media3 has no callbacks for ordinary progress. Sample actual position, never elapsed time.
        scope.launch { while (isActive) { delay(250); if (player.isPlaying) publish() } }
    }

    override suspend fun load(entry: QueueEntry, source: PlayableSource, positionMs: Long, playWhenReady: Boolean): AppResult<Unit> =
        withContext(Dispatchers.Main.immediate) {
            if (released) return@withContext AppResult.Failure(AppError(ErrorCode.SOURCE_UNAVAILABLE, "player.load", false))
            when (val built = sources.create(entry, source)) {
                is AppResult.Failure -> built
                is AppResult.Success -> {
                    this@AndroidPlayerEngine.entry = entry
                    track = source.track
                    player.setMediaSource(built.value, positionMs.coerceAtLeast(0))
                    player.playWhenReady = playWhenReady
                    player.prepare()
                    // Read Media3's state after the command, rather than marking the request playing.
                    publish()
                    AppResult.Success(Unit)
                }
            }
        }

    override fun play() { checkMain(); if (!released) player.play() }
    override fun pause() { checkMain(); if (!released) player.pause() }
    override fun seek(positionMs: Long) {
        checkMain()
        if (!released) {
            val duration = player.duration.takeIf { it != C.TIME_UNSET && it >= 0 }
            player.seekTo(positionMs.coerceAtLeast(0).let { duration?.let(it::coerceAtMost) ?: it })
        }
    }
    override fun stop() {
        checkMain()
        if (released) return
        entry = null
        track = null
        player.stop()
        player.clearMediaItems()
        state.value = PlaybackSnapshot()
    }
    override fun release() {
        checkMain()
        if (released) return
        stop()
        released = true
        scope.cancel()
        player.removeListener(listener)
        player.release()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
    private fun publish() {
        if (released || entry == null) return
        val error = player.playerError?.let { AppError(
            when (it.errorCode) {
                2004 -> ErrorCode.URL_REJECTED
                in 2000..2999 -> ErrorCode.NETWORK
                in 3000..4999 -> ErrorCode.INCOMPATIBLE_RESOURCE
                else -> ErrorCode.UNKNOWN
            }, "player.callback", it.errorCode in 2000..2999) }
        val status = when {
            error != null -> PlayerStatus.FAILED
            player.playbackState == Player.STATE_ENDED -> PlayerStatus.ENDED
            player.playbackState == Player.STATE_BUFFERING -> PlayerStatus.BUFFERING
            player.isPlaying -> PlayerStatus.PLAYING
            player.playbackState == Player.STATE_READY -> PlayerStatus.PAUSED
            else -> PlayerStatus.IDLE
        }
        state.value = PlaybackSnapshot(status, entry, track, player.currentPosition.coerceAtLeast(0),
            player.duration.takeIf { it != C.TIME_UNSET && it >= 0 }, player.playWhenReady, error)
    }
    private fun checkMain() { check(Looper.myLooper() == Looper.getMainLooper()) { "Player commands require the main thread" } }
}
