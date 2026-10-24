package app.musicplayer.queue

import app.musicplayer.fixtures.Fixtures
import app.musicplayer.model.*
import app.musicplayer.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

internal class PlaylistQueueFixture(
    scope: CoroutineScope,
    random: kotlin.random.Random = kotlin.random.Random.Default,
) {
    val tracks =
        listOf("a", "b", "c", "tail", "n1", "n2")
            .associate { TrackId(it) to Fixtures.track(it, sourceId = it) }
            .toMutableMap()
    var resolves = 0
    var beforeResolve: suspend (PlaybackCandidate) -> Unit = {}
    val engine = Engine()
    val coordinator =
        PlaybackCoordinator(
            engine,
            object : PlaybackResolver {
                override suspend fun resolve(
                    candidate: PlaybackCandidate,
                    capabilities: PlaybackCapabilities,
                    network: NetworkContext,
                ): AppResult<ResolvedPlayback> {
                    resolves++
                    beforeResolve(candidate)
                    return AppResult.Success(
                        ResolvedPlayback(
                            candidate.id,
                            Representation("wav", "audio/wav", "pcm", null, 187000, null),
                            TransportKind.PROGRESSIVE,
                            MediaRequest("https://example.invalid/audio", emptyMap()),
                            null,
                            network.generation,
                        )
                    )
                }
            },
            { NetworkContext(0, NetworkConfig(NetworkMode.SYSTEM, null, null), true, true) },
            scope,
        )
    val queue = QueueController(engine, coordinator, { tracks[it] }, random)

    init {
        scope.launch { coordinator.snapshot.collect { queue.onPlayback(it) } }
    }

    fun entry(id: String) = QueueEntry(QueueEntryId(id), TrackId(id))

    class Engine : PlayerEngine {
        override val snapshot = MutableStateFlow(PlaybackSnapshot())
        var loads = 0

        override suspend fun load(
            entry: QueueEntry,
            source: PlayableSource,
            positionMs: Long,
            playWhenReady: Boolean,
        ): AppResult<Unit> {
            loads++
            snapshot.value =
                PlaybackSnapshot(
                    PlayerStatus.PLAYING,
                    entry,
                    source.track,
                    positionMs,
                    187000,
                    playWhenReady,
                    loadId = (source as? PlayableSource.Remote)?.loadId,
                    bufferedPositionMs = 10000,
                )
            return AppResult.Success(Unit)
        }

        override fun play() {
            snapshot.value = snapshot.value.copy(playWhenReady = true)
        }

        override fun pause() {
            snapshot.value = snapshot.value.copy(playWhenReady = false)
        }

        override fun seek(positionMs: Long) {
            snapshot.value = snapshot.value.copy(positionMs = positionMs)
        }

        override fun stop() {
            snapshot.value = PlaybackSnapshot()
        }

        override fun release() {}
    }
}
