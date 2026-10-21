package app.musicplayer.playback

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.musicplayer.MusicApplication
import app.musicplayer.model.*
import app.musicplayer.queue.*
import java.util.concurrent.Executor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Activity owns connect/close; no Activity owns or releases the service's engine. */
class PlayerConnection(private val application: MusicApplication) :
    QueuePlaybackPort, AutoCloseable {
    enum class Status {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        FAILED,
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph = application.graph
    private val controllers =
        ControllerConnection(create = ::createController, release = MediaController::releaseFuture)
    val status: StateFlow<Status> = controllers.status
    private val state = MutableStateFlow(PlaybackSnapshot())
    override val snapshot: StateFlow<PlaybackSnapshot> = state.asStateFlow()
    private val queueState = MutableStateFlow(QueueSnapshot())
    override val queue: StateFlow<QueueSnapshot> = queueState.asStateFlow()
    private var closed = false
    private var commandGeneration = 0L

    init {
        scope.launch {
            graph.playback.collectLatest { host ->
                if (host == null) {
                    state.value = PlaybackSnapshot()
                    queueState.value = QueueSnapshot()
                } else
                    coroutineScope {
                        launch { host.snapshot.collect { state.value = it } }
                        host.queue.collect { queueState.value = it }
                    }
            }
        }
    }

    fun connect() {
        checkMain()
        controllers.connect()
    }

    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    private fun createController(
        callbacks: ControllerCallbacks
    ): com.google.common.util.concurrent.ListenableFuture<MediaController> {
        val pending =
            MediaController.Builder(
                    application,
                    SessionToken(
                        application,
                        ComponentName(application, PlaybackService::class.java),
                    ),
                )
                .setListener(
                    object : MediaController.Listener {
                        override fun onDisconnected(controller: MediaController) =
                            callbacks.disconnected()
                    }
                )
                .buildAsync()
        pending.addListener(
            {
                try {
                    pending.get()
                    callbacks.connected()
                } catch (_: Exception) {
                    callbacks.failed()
                }
            },
            Executor { Handler(Looper.getMainLooper()).post(it) },
        )
        return pending
    }

    override suspend fun select(
        track: Track,
        entry: QueueEntry,
        positionMs: Long,
        playWhenReady: Boolean,
    ) = withHost { it.select(track, entry, positionMs, playWhenReady) }

    override suspend fun playList(tracks: List<Track>, startIndex: Int) = withHost {
        it.playList(tracks, startIndex)
    }

    override suspend fun enqueue(track: Track) = withHost { it.enqueue(track) }

    private suspend fun <T> withHost(
        action: suspend (QueuePlaybackPort) -> AppResult<T>
    ): AppResult<T> =
        withContext(Dispatchers.Main.immediate) {
            val command = ++commandGeneration
            if (closed)
                return@withContext AppResult.Failure(
                    AppError(ErrorCode.SOURCE_UNAVAILABLE, "player.closed", false)
                )
            connect()
            val ready = withTimeoutOrNull(10000) { status.first { it != Status.CONNECTING } }
            if (command != commandGeneration)
                throw CancellationException("Superseded player command")
            val host = graph.playback.value
            if (ready != Status.CONNECTED || host == null)
                AppResult.Failure(AppError(ErrorCode.SOURCE_UNAVAILABLE, "player.connect", true))
            else action(host)
        }

    override suspend fun dispatch(command: QueueCommand) {
        withContext(Dispatchers.Main.immediate) {
            val commandId = ++commandGeneration
            connect()
            val ready = withTimeoutOrNull(10000) { status.first { it != Status.CONNECTING } }
            if (commandId == commandGeneration && ready == Status.CONNECTED)
                graph.playback.value?.dispatch(command)
        }
    }

    override fun play() {
        checkMain()
        ++commandGeneration
        graph.playback.value?.play()
    }

    override fun pause() {
        checkMain()
        ++commandGeneration
        graph.playback.value?.pause()
    }

    override fun seek(positionMs: Long) {
        checkMain()
        ++commandGeneration
        graph.playback.value?.seek(positionMs)
    }

    override fun stop() {
        checkMain()
        ++commandGeneration
        graph.playback.value?.stop()
    }

    override fun close() {
        checkMain()
        if (closed) return
        closed = true
        controllers.close()
        scope.cancel()
    }

    private fun checkMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Player connection requires the main thread"
        }
    }
}
