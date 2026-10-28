package app.musicplayer.desktop

import app.musicplayer.model.*
import app.musicplayer.playback.*
import app.musicplayer.queue.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class DesktopPlaybackPort(
    private val engine: PlayerEngine,
    private val coordinator: PlaybackCoordinator,
    private val scope: CoroutineScope,
) : QueuePlaybackPort {
    private val tracks = mutableMapOf<TrackId, Track>()
    private val controller = QueueController(engine, coordinator, { tracks[it] })
    override val queue = controller.state
    override val snapshot = controller.snapshot

    init {
        scope.launch { coordinator.snapshot.collect { controller.onPlayback(it) } }
        scope.launch {
            queue.collect { state ->
                tracks.keys.retainAll(state.entries.map { it.trackId }.toSet())
            }
        }
    }

    private fun entry(track: Track) =
        QueueEntry(QueueEntryId(UUID.randomUUID().toString()), track.id)

    fun track(id: TrackId): Track? = tracks[id]

    override suspend fun playList(tracks: List<Track>, startIndex: Int): AppResult<Unit> {
        if (startIndex !in tracks.indices)
            return AppResult.Failure(AppError(ErrorCode.INVALID_CONFIG, "desktop.queue", false))
        tracks.forEach { this.tracks[it.id] = it }
        val entries = tracks.map(::entry)
        return controller.replace(entries, entries[startIndex].id)
    }

    override suspend fun enqueue(track: Track, position: QueueInsertion): AppResult<Unit> {
        tracks[track.id] = track
        controller.dispatch(QueueCommand.Add(listOf(entry(track)), position))
        return AppResult.Success(Unit)
    }

    override suspend fun select(
        track: Track,
        entry: QueueEntry,
        positionMs: Long,
        playWhenReady: Boolean,
    ): AppResult<Unit> {
        tracks[track.id] = track
        return controller.select(entry, positionMs, playWhenReady)
    }

    override suspend fun dispatch(command: QueueCommand) = controller.dispatch(command)

    override fun play() {
        scope.launch {
            controller.play()
            engine.play()
        }
    }

    override fun pause() {
        scope.launch {
            controller.pause()
            engine.pause()
        }
    }

    override fun seek(positionMs: Long) {
        scope.launch { controller.seek(positionMs) }
    }

    override fun stop() {
        scope.launch { controller.stop() }
    }
}
