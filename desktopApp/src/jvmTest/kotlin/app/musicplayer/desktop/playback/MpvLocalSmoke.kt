package app.musicplayer.desktop.playback

import app.musicplayer.model.*
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

object MpvLocalSmoke {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val engine = MpvPlayerEngine(scope) { MpvProcess.connect(scope) }
        try {
            val track = fixtureTrack()
            val source =
                PlayableSource.Local(
                    AssetId("smoke"),
                    Path.of(args.single()).toUri().toString(),
                    "audio/wav",
                    "smoke",
                    track,
                    1,
                )
            check(
                engine.load(QueueEntry(QueueEntryId("smoke"), track.id), source, 0, true)
                    is AppResult.Success
            )
            withTimeout(10000) {
                engine.snapshot.first { it.status == PlayerStatus.PLAYING && it.positionMs > 0 }
            }
            engine.pause()
            withTimeout(5000) { engine.snapshot.first { it.status == PlayerStatus.PAUSED } }
            engine.seek(10000)
            withTimeout(5000) { engine.snapshot.first { it.positionMs in 9800..10200 } }
            engine.play()
            withTimeout(5000) {
                engine.snapshot.first { it.status == PlayerStatus.PLAYING && it.positionMs > 10300 }
            }
            println("Local playback, pause, seek and resume completed")
        } finally {
            engine.release()
            scope.cancel()
        }
    }
}
