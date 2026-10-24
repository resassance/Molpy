package app.musicplayer.playback

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.musicplayer.MusicApplication
import app.musicplayer.PlaybackRuntime
import app.musicplayer.model.*
import app.musicplayer.queue.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Owns the engine, resolver lifetime and coordinator independently of Activity recreation. */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runtime: PlaybackRuntime
    private var session: MediaSession? = null
    private lateinit var host: QueuePlaybackPort

    override fun onCreate() {
        super.onCreate()
        val graph = (application as MusicApplication).graph
        runtime = graph.createPlayback(scope)
        val controller = runtime.queue
        val commands = runtime.commands
        scope.launch { runtime.coordinator.snapshot.collect { controller.onPlayback(it) } }
        host =
            object : QueuePlaybackPort {
                override val snapshot = runtime.persistence.snapshot
                override val queue = controller.state

                override suspend fun enqueue(
                    track: Track,
                    position: app.musicplayer.queue.QueueInsertion,
                ): AppResult<Unit> =
                    scope
                        .async {
                            runtime.startup.await()
                            commands.enqueue(track, position)
                        }
                        .await()

                override suspend fun playList(
                    tracks: List<Track>,
                    startIndex: Int,
                ): AppResult<Unit> =
                    scope
                        .async {
                            runtime.startup.await()
                            commands.playList(tracks, startIndex)
                        }
                        .await()

                override suspend fun playShuffledList(tracks: List<Track>): AppResult<Unit> =
                    scope
                        .async {
                            runtime.startup.await()
                            commands.playShuffledList(tracks)
                        }
                        .await()

                override suspend fun playPublicPlaylist(
                    request: PlaylistPlaybackRequest
                ): AppResult<Unit> =
                    scope
                        .async {
                            runtime.startup.await()
                            runtime.playlistLoader.play(request)
                        }
                        .await()

                override suspend fun retryQueueSource(): AppResult<Unit> =
                    scope
                        .async {
                            runtime.startup.await()
                            runtime.playlistLoader.retry()
                        }
                        .await()

                override suspend fun select(
                    track: Track,
                    entry: QueueEntry,
                    positionMs: Long,
                    playWhenReady: Boolean,
                ): AppResult<Unit> =
                    scope
                        .async {
                            runtime.startup.await()
                            commands.select(track, entry, positionMs, playWhenReady)
                        }
                        .await()

                override suspend fun dispatch(command: QueueCommand) {
                    scope
                        .async {
                            runtime.startup.await()
                            commands.dispatch(command)
                        }
                        .await()
                }

                override fun play() {
                    commands.invalidatePending()
                    scope.launch {
                        runtime.startup.await()
                        runtime.playlistLoader.resumeIfNeeded()
                        controller.play()
                    }
                }

                override fun pause() {
                    commands.invalidatePending()
                    scope.launch {
                        runtime.startup.await()
                        controller.pause()
                    }
                }

                override fun seek(positionMs: Long) {
                    commands.invalidatePending()
                    scope.launch {
                        runtime.startup.await()
                        controller.seek(positionMs)
                    }
                }

                override fun stop() {
                    commands.invalidateQueue()
                    runtime.playlistLoader.stop()
                    scope.launch {
                        runtime.startup.await()
                        runtime.persistence.stop()
                    }
                }
            }
        // Media3 holds only the current resolved source; queue navigation belongs to the
        // controller.
        val sessionPlayer =
            QueueSessionPlayer(runtime.engine.player, host) { command ->
                scope.launch {
                    runtime.startup.await()
                    commands.dispatch(command)
                }
            }
        session = MediaSession.Builder(this, sessionPlayer).build()
        scope.launch { controller.state.collect { sessionPlayer.onQueueChanged() } }
        scope.launch {
            runtime.startup.await()
            graph.playback.value = host
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        scope.launch {
            runtime.startup.await()
            runtime.persistence.checkpoint()
            runtime.listening.checkpoint()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val graph = (application as MusicApplication).graph
        if (graph.playback.value === host) graph.playback.value = null
        runtime.playlistLoader.close()
        runtime.listening.ownerStopped()
        scope.cancel()
        session?.release()
        session = null
        // A separate teardown job survives owner cancellation. Save before release clears actual
        // state.
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                try {
                    runtime.persistence.close()
                } finally {
                    runtime.listening.close()
                }
            } finally {
                runtime.coordinator.release()
                runtime.closeExtraction()
            }
        }
        super.onDestroy()
    }
}
