package app.musicplayer.desktop

import kotlin.test.*
import kotlinx.coroutines.*

class DesktopGraphTest {
    @Test
    fun closeIsIdempotentAndReleasesOwnedJobs() = runBlocking {
        val owner = CoroutineScope(coroutineContext + SupervisorJob())
        val directory = java.nio.file.Files.createTempDirectory("molpy-graph-test-")
        val graph = DesktopGraph(owner, directory)
        graph.close()
        graph.close()
        assertTrue(owner.coroutineContext[Job]!!.children.none())
        owner.cancel()
        directory.toFile().deleteRecursively()
        Unit
    }
}
