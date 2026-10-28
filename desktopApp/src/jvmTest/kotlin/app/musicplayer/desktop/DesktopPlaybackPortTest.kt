package app.musicplayer.desktop

import app.musicplayer.desktop.playback.fixtureTrack
import app.musicplayer.model.*
import app.musicplayer.playback.*
import app.musicplayer.queue.*
import kotlin.test.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DesktopPlaybackPortTest {
    private class Engine : PlayerEngine {
        override val snapshot = MutableStateFlow(PlaybackSnapshot())

        override suspend fun load(
            entry: QueueEntry,
            source: PlayableSource,
            positionMs: Long,
            playWhenReady: Boolean,
        ): AppResult<Unit> = AppResult.Success(Unit)

        override fun play() = Unit

        override fun pause() = Unit

        override fun seek(positionMs: Long) = Unit

        override fun stop() = Unit

        override fun release() = Unit
    }

    @Test
    fun repeatedTrackOccurrencesRemainDistinctAndInvalidIndexDoesNotDestroyQueue() = runTest {
        val engine = Engine()
        val resolver =
            object : PlaybackResolver {
                override suspend fun resolve(
                    candidate: PlaybackCandidate,
                    capabilities: PlaybackCapabilities,
                    network: NetworkContext,
                ): AppResult<ResolvedPlayback> =
                    AppResult.Failure(AppError(ErrorCode.SOURCE_UNAVAILABLE, "test", false))
            }
        val coordinator =
            PlaybackCoordinator(
                engine,
                resolver,
                { NetworkContext(1, NetworkConfig(NetworkMode.SYSTEM, null, null), true, true) },
                backgroundScope,
            )
        val port = DesktopPlaybackPort(engine, coordinator, backgroundScope)
        val track = fixtureTrack()
        port.playList(listOf(track, track), 1)
        assertEquals(2, port.queue.value.entries.size)
        assertNotEquals(port.queue.value.entries[0].id, port.queue.value.entries[1].id)
        val before = port.queue.value
        assertTrue(port.playList(emptyList(), 0) is AppResult.Failure)
        assertEquals(before, port.queue.value)
        port.enqueue(track, QueueInsertion.NEXT)
        assertEquals(3, port.queue.value.entries.size)
        coordinator.release()
    }
}
