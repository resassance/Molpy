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
class QueueSourceSessionTest {
    @Test
    fun pendingSourceOffersNextWithoutEnablingNativeShuffle() = runBlocking {
        withContext(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val native = ExoPlayer.Builder(context).build()
            try {
                val host = Host()
                val calls = mutableListOf<QueueCommand>()
                val entry = QueueEntry(QueueEntryId("a"), TrackId("a"))
                host.queue.value =
                    QueueSnapshot(
                        listOf(entry),
                        entry.id,
                        source =
                            QueueSourceState(
                                "source",
                                app.musicplayer.catalog.PublicPlaylistRef(
                                    "List",
                                    SourceRef("youtube", "playlist", "PLsource"),
                                ),
                                0,
                                listOf(
                                    app.musicplayer.catalog.PlaylistRowIdentity(
                                        0,
                                        "row-0",
                                        SourceRef("youtube", "video", "a"),
                                    )
                                ),
                                shuffleRequested = true,
                            ),
                    )
                val session = QueueSessionPlayer(native, host) { calls.add(it) }
                assertTrue(session.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
                assertFalse(session.shuffleModeEnabled)
                assertFalse(native.shuffleModeEnabled)
                session.seekToNextMediaItem()
                assertEquals(listOf(QueueCommand.Next), calls)
                host.queue.value =
                    host.queue.value.copy(source = host.queue.value.source!!.copy(waiting = true))
                session.onQueueChanged()
                assertTrue(session.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
                session.shuffleModeEnabled = false
                assertEquals(QueueCommand.SetShuffle(false), calls.last())
                assertFalse(native.shuffleModeEnabled)
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
