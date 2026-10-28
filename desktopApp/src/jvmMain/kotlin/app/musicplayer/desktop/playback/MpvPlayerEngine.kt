package app.musicplayer.desktop.playback

import app.musicplayer.model.*
import app.musicplayer.playback.PlayerEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal class MpvPlayerEngine(
    private val scope: CoroutineScope,
    private val connect: suspend () -> MpvSession,
) : PlayerEngine {
    private val state = MutableStateFlow(PlaybackSnapshot())
    override val snapshot = state.asStateFlow()
    private var epoch = 0L
    private var released = false
    private var desiredPlay = false
    private var session: MpvSession? = null
    private var watch: Job? = null
    private var poll: Job? = null

    override suspend fun load(
        entry: QueueEntry,
        source: PlayableSource,
        positionMs: Long,
        playWhenReady: Boolean,
    ): AppResult<Unit> {
        stop()
        if (released) return failure()
        val selected = epoch
        desiredPlay = playWhenReady
        val loadId =
            when (source) {
                is PlayableSource.Remote -> source.loadId
                is PlayableSource.Local -> source.loadId
            }
        val candidate =
            when (source) {
                is PlayableSource.Remote -> source.lease.candidateId
                is PlayableSource.Local -> source.candidateId
            }
        state.value =
            PlaybackSnapshot(
                PlayerStatus.BUFFERING,
                entry,
                source.track,
                positionMs,
                source.track.durationMs,
                playWhenReady,
                loadId = loadId,
                candidateId = candidate,
            )
        var created: MpvSession? = null
        try {
            created = connect()
            if (selected != epoch || released) {
                created.close()
                return failure()
            }
            session = created
            val active = created
            watch =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    active.events.collect { event ->
                        if (selected != epoch) return@collect
                        when (event["event"]?.jsonPrimitive?.content) {
                            "end-file" -> {
                                val reason = event["reason"]?.jsonPrimitive?.content
                                if (reason == "eof")
                                    state.value =
                                        state.value.copy(
                                            status = PlayerStatus.ENDED,
                                            playWhenReady = false,
                                        )
                                else if (reason == "error") fail(selected)
                            }
                            "shutdown" -> fail(selected)
                        }
                    }
                }
            val headers =
                when (source) {
                    is PlayableSource.Remote -> source.lease.request.headers
                    is PlayableSource.Local -> emptyMap()
                }
            val uri =
                when (source) {
                    is PlayableSource.Remote -> source.lease.request.uri
                    is PlayableSource.Local -> source.stableUri
                }
            active.command(
                listOf(
                    JsonPrimitive("set_property"),
                    JsonPrimitive("http-header-fields"),
                    JsonArray(headers.map { JsonPrimitive("${it.key}: ${it.value}") }),
                )
            )
            active.command(
                listOf(
                    JsonPrimitive("set_property"),
                    JsonPrimitive("pause"),
                    JsonPrimitive(!desiredPlay),
                )
            )
            active.command(
                listOf(
                    JsonPrimitive("loadfile"),
                    JsonPrimitive(uri),
                    JsonPrimitive("replace"),
                    JsonPrimitive(-1),
                    buildJsonObject {
                        put("start", (positionMs.coerceAtLeast(0) / 1000.0).toString())
                    },
                )
            )
            state.value = state.value.copy(playWhenReady = desiredPlay)
            poll = scope.launch {
                while (isActive && selected == epoch) {
                    try {
                        suspend fun property(name: String): JsonElement =
                            try {
                                active.call("get_property", name)
                            } catch (_: MpvCommandException) {
                                JsonNull
                            }
                        val time = property("time-pos").jsonPrimitive.doubleOrNull
                        val duration = property("duration").jsonPrimitive.doubleOrNull
                        val paused = property("pause").jsonPrimitive.booleanOrNull ?: !desiredPlay
                        val buffering =
                            property("paused-for-cache").jsonPrimitive.booleanOrNull ?: false
                        if (
                            selected == epoch &&
                                state.value.status !in
                                    setOf(PlayerStatus.ENDED, PlayerStatus.FAILED)
                        ) {
                            state.value =
                                state.value.copy(
                                    status =
                                        if (time == null || buffering) PlayerStatus.BUFFERING
                                        else if (paused) PlayerStatus.PAUSED
                                        else PlayerStatus.PLAYING,
                                    positionMs =
                                        time?.let { (it * 1000).toLong().coerceAtLeast(0) }
                                            ?: state.value.positionMs,
                                    durationMs =
                                        duration?.let { (it * 1000).toLong().takeIf { v -> v > 0 } }
                                            ?: state.value.durationMs,
                                    playWhenReady = !paused,
                                )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        fail(selected)
                        break
                    }
                    delay(250)
                }
            }
            return AppResult.Success(Unit)
        } catch (e: CancellationException) {
            created?.close()
            if (selected == epoch) stop()
            throw e
        } catch (_: Exception) {
            created?.close()
            fail(selected)
            return failure()
        }
    }

    private fun failure() =
        AppResult.Failure(AppError(ErrorCode.SOURCE_UNAVAILABLE, "desktop.mpv", true))

    private fun fail(selected: Long) {
        if (selected != epoch || state.value.current == null) return
        watch?.cancel()
        poll?.cancel()
        session?.close()
        session = null
        state.value =
            state.value.copy(
                status = PlayerStatus.FAILED,
                playWhenReady = false,
                error = failure().error,
            )
    }

    private fun send(vararg args: JsonElement) {
        val active = session ?: return
        val selected = epoch
        scope.launch {
            try {
                active.command(args.toList())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fail(selected)
            }
        }
    }

    override fun play() {
        desiredPlay = true
        state.value = state.value.copy(playWhenReady = true)
        send(JsonPrimitive("set_property"), JsonPrimitive("pause"), JsonPrimitive(false))
    }

    override fun pause() {
        desiredPlay = false
        state.value = state.value.copy(playWhenReady = false)
        send(JsonPrimitive("set_property"), JsonPrimitive("pause"), JsonPrimitive(true))
    }

    override fun seek(positionMs: Long) =
        send(
            JsonPrimitive("seek"),
            JsonPrimitive(positionMs.coerceAtLeast(0) / 1000.0),
            JsonPrimitive("absolute+exact"),
        )

    override fun stop() {
        epoch++
        watch?.cancel()
        poll?.cancel()
        session?.close()
        session = null
        state.value = PlaybackSnapshot()
    }

    override fun release() {
        released = true
        stop()
    }
}
