package app.musicplayer.playback

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import app.musicplayer.diagnostics.DiagnosticEvent
import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.Call

/** The service owns this engine. All Media3 calls and listeners run on Android's main looper. */
@UnstableApi
class AndroidPlayerEngine(
    context: Context,
    callFactoryFor: (Long) -> Call.Factory,
    localDataSource: androidx.media3.datasource.DataSource.Factory?,
    private val diagnostics: (DiagnosticEvent) -> Unit = {},
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : PlayerEngine {
    constructor(
        context: Context,
        callFactoryFor: (Long) -> Call.Factory,
    ) : this(context, callFactoryFor, null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sources = MediaSourceFactory(callFactoryFor, localDataSource)
    internal val player: ExoPlayer
    private val state = MutableStateFlow(PlaybackSnapshot())
    override val snapshot: StateFlow<PlaybackSnapshot> = state.asStateFlow()
    private var entry: QueueEntry? = null
    private var track: Track? = null
    private var loadId: Long? = null
    private var local = false
    private var candidateId: CandidateId? = null
    private var released = false
    private val startupTiming = PlaybackStartupTiming()
    private val audioListener =
        object : AnalyticsListener {
            override fun onAudioPositionAdvancing(
                eventTime: AnalyticsListener.EventTime,
                playoutStartSystemTimeMs: Long,
            ) {
                if (released || eventTime.windowIndex !in 0 until eventTime.timeline.windowCount)
                    return
                val eventLoad =
                    eventTime.timeline
                        .getWindow(eventTime.windowIndex, Timeline.Window())
                        .mediaItem
                        .localConfiguration
                        ?.tag as? Long
                val elapsed = startupTiming.firstAudio(eventLoad, clock()) ?: return
                diagnostics(
                    DiagnosticEvent(
                        "player-load-$eventLoad",
                        "first_audio",
                        null,
                        candidateId?.value,
                        null,
                        null,
                        elapsed,
                    )
                )
            }
        }
    private val listener =
        object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                publish()
            }
        }

    init {
        checkMain()
        player =
            ExoPlayer.Builder(context.applicationContext)
                .setLooper(Looper.getMainLooper())
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                )
                .setHandleAudioBecomingNoisy(true)
                .build()
        player.addListener(listener)
        player.addAnalyticsListener(audioListener)
        // Media3 has no callbacks for ordinary progress. Sample actual position, never elapsed
        // time.
        scope.launch {
            while (isActive) {
                delay(250)
                if (
                    player.isPlaying ||
                        player.isLoading ||
                        player.playbackState == Player.STATE_BUFFERING
                )
                    publish()
            }
        }
    }

    override suspend fun load(
        entry: QueueEntry,
        source: PlayableSource,
        positionMs: Long,
        playWhenReady: Boolean,
    ): AppResult<Unit> =
        withContext(Dispatchers.Main.immediate) {
            if (released)
                return@withContext AppResult.Failure(
                    AppError(ErrorCode.SOURCE_UNAVAILABLE, "player.load", false)
                )
            when (val built = sources.create(entry, source)) {
                is AppResult.Failure -> built
                is AppResult.Success -> {
                    this@AndroidPlayerEngine.entry = entry
                    track = source.track
                    loadId =
                        when (source) {
                            is PlayableSource.Local -> source.loadId
                            is PlayableSource.Remote -> source.loadId
                        }
                    local = source is PlayableSource.Local
                    candidateId =
                        when (source) {
                            is PlayableSource.Local -> source.candidateId
                            is PlayableSource.Remote -> source.lease.candidateId
                        }
                    startupTiming.start(loadId, clock())
                    player.setMediaSource(built.value, positionMs.coerceAtLeast(0))
                    player.playWhenReady = playWhenReady
                    player.prepare()
                    // Read Media3's state after the command, rather than marking the request
                    // playing.
                    publish()
                    AppResult.Success(Unit)
                }
            }
        }

    override fun play() {
        checkMain()
        if (!released) player.play()
    }

    override fun pause() {
        checkMain()
        if (!released) player.pause()
    }

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
        loadId = null
        candidateId = null
        startupTiming.clear()
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
        player.removeAnalyticsListener(audioListener)
        player.release()
    }

    private fun publish() {
        if (released || entry == null) return
        if (player.currentMediaItem?.localConfiguration?.tag != loadId) return
        val progress =
            samplePlayerProgress(player.currentPosition, player.duration, player.bufferedPosition)
        val error =
            player.playerError?.let {
                if (local) AppError(ErrorCode.INCOMPLETE_MEDIA, "player.local", false)
                else normalizeMedia3Failure(it)
            }
        val status =
            when {
                error != null -> PlayerStatus.FAILED
                player.playbackState == Player.STATE_ENDED -> PlayerStatus.ENDED
                player.playbackState == Player.STATE_BUFFERING -> PlayerStatus.BUFFERING
                player.isPlaying -> PlayerStatus.PLAYING
                player.playbackState == Player.STATE_READY -> PlayerStatus.PAUSED
                else -> PlayerStatus.IDLE
            }
        state.value =
            PlaybackSnapshot(
                status,
                entry,
                track,
                progress.positionMs,
                progress.durationMs,
                player.playWhenReady,
                error,
                loadId,
                candidateId,
                bufferedPositionMs = progress.bufferedPositionMs,
            )
    }

    private fun checkMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Player commands require the main thread"
        }
    }
}

/** Inspect Media3 1.11.1's typed cause; never parse exception text or retain response data. */
@UnstableApi
internal fun normalizeMedia3Failure(error: PlaybackException): AppError {
    var cause: Throwable? = error
    val seen = mutableSetOf<Throwable>()
    while (cause != null && seen.add(cause)) {
        if (cause is HttpDataSource.InvalidResponseCodeException)
            return mapPlayerFailure(error.errorCode, cause.responseCode)
        cause = cause.cause
    }
    return mapPlayerFailure(error.errorCode, null)
}
