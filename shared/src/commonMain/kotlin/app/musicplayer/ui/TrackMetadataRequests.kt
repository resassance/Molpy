package app.musicplayer.ui

import app.musicplayer.catalog.TrackMetadataLoader
import app.musicplayer.model.*
import kotlinx.coroutines.*

internal class TrackMetadataRequests(
    private val loader: TrackMetadataLoader?,
    private val scope: CoroutineScope,
    initiallyVisible: Boolean = true,
) {
    private val requested = mutableSetOf<Pair<TrackId, Set<SourceRef>>>()
    private val jobs = mutableSetOf<Job>()
    private var generation = 0L
    private var networkReady = true
    private var visible = initiallyVisible

    fun load(tracks: List<Track>, onUpdated: (List<Track>) -> Unit) {
        val provider = loader ?: return
        if (!visible) return
        val fresh =
            tracks
                .distinctBy { it.id }
                .filter {
                    requested.add(
                        it.id to it.candidates.map { candidate -> candidate.source }.toSet()
                    )
                }
        if (fresh.isEmpty()) return
        val selected = generation
        val allowRemote = networkReady
        val task =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    provider.load(fresh, allowRemote = allowRemote) {
                        if (selected == generation && scope.isActive) onUpdated(it)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {}
            }
        jobs += task
        task.invokeOnCompletion { jobs -= task }
        task.start()
    }

    fun reset() {
        generation++
        jobs.toList().forEach(Job::cancel)
        jobs.clear()
        requested.clear()
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        reset()
    }

    fun pause() {
        networkReady = false
        reset()
    }

    fun resume() {
        networkReady = true
        reset()
    }
}
