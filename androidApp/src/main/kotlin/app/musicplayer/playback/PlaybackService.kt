package app.musicplayer.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.musicplayer.MusicApplication
import app.musicplayer.PlaybackRuntime
import app.musicplayer.model.*
import kotlinx.coroutines.*

/** Owns the engine, resolver lifetime and coordinator independently of Activity recreation. */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runtime: PlaybackRuntime
    private var session: MediaSession? = null
    private lateinit var host: PlaybackPort

    override fun onCreate() {
        super.onCreate()
        val graph = (application as MusicApplication).graph
        runtime = graph.createPlayback(scope)
        val coordinator = runtime.coordinator
        host = object : PlaybackPort {
            override val snapshot = coordinator.snapshot
            override suspend fun select(track: Track, entry: QueueEntry, positionMs: Long, playWhenReady: Boolean) =
                scope.async { coordinator.select(track, entry, positionMs, playWhenReady) }.await()
            override fun play() = coordinator.play()
            override fun pause() = coordinator.pause()
            override fun seek(positionMs: Long) = coordinator.seek(positionMs)
            override fun stop() = coordinator.stop()
        }
        // System controls update the same intent during resolution as commands from the app.
        val sessionPlayer = object : ForwardingPlayer(runtime.engine.player) {
            override fun play() = coordinator.play()
            override fun pause() = coordinator.pause()
            override fun setPlayWhenReady(playWhenReady: Boolean) { if (playWhenReady) coordinator.play() else coordinator.pause() }
            override fun seekTo(positionMs: Long) = coordinator.seek(positionMs)
            override fun seekTo(mediaItemIndex: Int, positionMs: Long) { if (mediaItemIndex == 0) coordinator.seek(positionMs) }
            override fun stop() = coordinator.stop()
        }
        session = MediaSession.Builder(this, sessionPlayer).build()
        graph.playback.value = host
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        val graph = (application as MusicApplication).graph
        if (graph.playback.value === host) graph.playback.value = null
        scope.cancel()
        session?.release()
        session = null
        runtime.coordinator.release()
        // Borrowed HTTP outlives extraction/cipher disposal, even after service scope cancellation.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runtime.closeExtraction() }
        super.onDestroy()
    }
}
