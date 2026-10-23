package app.musicplayer.playback

import app.musicplayer.artists.*
import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class ArtistMetadataObserver(
    snapshot: StateFlow<PlaybackSnapshot>,
    directory: Flow<AppResult<ArtistDirectory>>,
    update: (TrackId, QueueEntryId, Long, List<String>) -> Boolean,
    ownerScope: CoroutineScope,
) {
    private val job = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + job)

    private data class Update(
        val trackId: TrackId,
        val entryId: QueueEntryId,
        val loadId: Long,
        val names: List<String>,
    )

    init {
        scope.launch {
            val retained =
                directory
                    .catch {
                        if (it is CancellationException) throw it
                        emit(AppResult.Failure(AppError(ErrorCode.DATABASE, "artists.media", true)))
                    }
                    .runningFold(ArtistDirectory()) { old, result -> result.valueOrNull() ?: old }
            combine(snapshot, retained) { playing, artists ->
                    val track = playing.track
                    val entry = playing.current
                    val load = playing.loadId
                    if (track == null || entry == null || load == null || entry.trackId != track.id)
                        null
                    else
                        Update(
                            track.id,
                            entry.id,
                            load,
                            ArtistResolver.present(track, artists)
                                .artists
                                .map { it.name }
                                .filter { it.isNotBlank() },
                        )
                }
                .distinctUntilChanged()
                .collect { value ->
                    value?.let { update(it.trackId, it.entryId, it.loadId, it.names) }
                }
        }
    }

    fun close() {
        job.cancel()
    }
}
