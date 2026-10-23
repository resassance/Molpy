package app.musicplayer.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.musicplayer.model.*
import app.musicplayer.queue.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class QueueSessionShuffleTest {
    @Test
    fun sessionShuffleUsesApplicationQueueWithoutChangingNativePlayer() = runBlocking {
        withContext(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val native = ExoPlayer.Builder(context).build()
            try {
                val host = Host()
                val calls = mutableListOf<QueueCommand>()
                val session = QueueSessionPlayer(native, host) { calls.add(it) }
                val changes = mutableListOf<Boolean>()
                val listener =
                    object : Player.Listener {
                        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                            changes.add(shuffleModeEnabled)
                        }
                    }
                session.addListener(listener)
                session.onQueueChanged()
                changes.clear()
                assertTrue(session.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE))
                session.shuffleModeEnabled = true
                assertEquals(listOf(QueueCommand.SetShuffle(true)), calls)
                assertFalse(native.shuffleModeEnabled)
                host.queue.value =
                    QueueSnapshot(shuffleEnabled = true, traversal = QueueTraversal())
                session.onQueueChanged()
                session.onQueueChanged()
                assertTrue(session.shuffleModeEnabled)
                assertEquals(listOf(true), changes)
                session.removeListener(listener)
                host.queue.value = QueueSnapshot(traversal = QueueTraversal())
                session.onQueueChanged()
                assertEquals(listOf(true), changes)
            } finally {
                native.release()
            }
        }
    }

    private class Host : QueuePlaybackPort {
        override val snapshot = MutableStateFlow(PlaybackSnapshot())
        override val queue = MutableStateFlow(QueueSnapshot())

        override suspend fun enqueue(track: Track, position: QueueInsertion): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun playList(tracks: List<Track>, startIndex: Int): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun dispatch(command: QueueCommand) {}

        override suspend fun select(
            track: Track,
            entry: QueueEntry,
            positionMs: Long,
            playWhenReady: Boolean,
        ): AppResult<Unit> = AppResult.Success(Unit)

        override fun play() {}

        override fun pause() {}

        override fun seek(positionMs: Long) {}

        override fun stop() {}
    }
}
