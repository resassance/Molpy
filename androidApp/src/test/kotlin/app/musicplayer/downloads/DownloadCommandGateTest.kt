package app.musicplayer.downloads

import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class DownloadCommandGateTest {
    private val key = DownloadPreparationKey(TrackId("song"), CandidateId("source"))

    @Test
    fun slowPreparationDoesNotBlockProgressOrOtherCommands() = runBlocking {
        val gate = DownloadCommandGate()
        val release = CompletableDeferred<Unit>()
        val preparing =
            async(start = CoroutineStart.UNDISPATCHED) {
                gate.prepare(
                    key,
                    {
                        release.await()
                        AppResult.Success("lease")
                    },
                ) {
                    AppResult.Success(it)
                }
            }
        val progress = async(start = CoroutineStart.UNDISPATCHED) { gate.mutex.withLock { true } }
        assertTrue(
            "Progress must acquire the owner lock while resolution is pending",
            progress.isCompleted,
        )
        release.complete(Unit)
        assertEquals("lease", preparing.await().valueOrNull())
    }

    @Test
    fun cancellationCannotPublishAnAssetAfterRemoval() = runBlocking {
        val gate = DownloadCommandGate()
        val release = CompletableDeferred<Unit>()
        var committed = false
        val preparing =
            async(start = CoroutineStart.UNDISPATCHED) {
                gate.prepare(
                    key,
                    {
                        release.await()
                        AppResult.Success("lease")
                    },
                ) {
                    committed = true
                    AppResult.Success(it)
                }
            }
        val removing =
            async(start = CoroutineStart.UNDISPATCHED) {
                gate.mutex.withLock { gate.invalidate(key) }
            }
        assertTrue("Removal must not wait for the resolver", removing.isCompleted)
        release.complete(Unit)
        assertEquals(ErrorCode.RESOURCE_CHANGED, preparing.await().errorOrNull()?.code)
        assertFalse(committed)
    }

    @Test
    fun newerPreparationWinsForSameTrackAndCandidate() = runBlocking {
        val gate = DownloadCommandGate()
        val release = CompletableDeferred<Unit>()
        val first =
            async(start = CoroutineStart.UNDISPATCHED) {
                gate.prepare(
                    key,
                    {
                        release.await()
                        AppResult.Success("old")
                    },
                ) {
                    AppResult.Success(it)
                }
            }
        val second =
            async(start = CoroutineStart.UNDISPATCHED) {
                gate.prepare(key, { AppResult.Success("new") }) { AppResult.Success(it) }
            }
        assertTrue(second.isCompleted)
        assertEquals("new", second.await().valueOrNull())
        release.complete(Unit)
        assertEquals(ErrorCode.RESOURCE_CHANGED, first.await().errorOrNull()?.code)
    }
}
