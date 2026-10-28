package app.musicplayer.downloads

import app.musicplayer.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DownloadPreparationQueueTest {
    private val source = SourceRef("fixture", "audio", "song")
    private val candidate =
        PlaybackCandidate(CandidateId("source"), source, "original", 60000, MatchConfidence.HIGH)
    private val track =
        Track(
            TrackId("song"),
            "Song",
            emptyList(),
            null,
            Artwork(ArtworkKind.PLACEHOLDER, null, null),
            60000,
            setOf(source),
            MetadataProvenance(source, 0, false),
            listOf(candidate),
        )

    @Test
    fun visibleBeforeResolutionAndRepeatedEnqueueSharesOneTask() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val queue = DownloadPreparationQueue(DownloadCommandGate(), scope)
        val release = CompletableDeferred<Unit>()
        var calls = 0
        suspend fun enqueue() =
            queue.enqueue(
                track,
                candidate,
                {
                    calls++
                    release.await()
                    AppResult.Success("lease")
                },
            ) {
                AppResult.Success(AssetId("asset"))
            }
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { enqueue() }
            assertEquals(track, queue.states.value.single().track)
            val second = async(start = CoroutineStart.UNDISPATCHED) { enqueue() }
            assertEquals(1, calls)
            release.complete(Unit)
            assertEquals(AssetId("asset"), first.await().valueOrNull())
            assertEquals(AssetId("asset"), second.await().valueOrNull())
            assertTrue(queue.states.value.isEmpty())
        } finally {
            release.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun cancelRejectsLateResultWithoutDeletingNewPreparation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val queue = DownloadPreparationQueue(DownloadCommandGate(), scope)
        val oldRelease = CompletableDeferred<Unit>()
        val newRelease = CompletableDeferred<Unit>()
        var oldCommitted = false
        try {
            val old =
                async(start = CoroutineStart.UNDISPATCHED) {
                    queue.enqueue(
                        track,
                        candidate,
                        {
                            withContext(NonCancellable) { oldRelease.await() }
                            AppResult.Success("old")
                        },
                    ) {
                        oldCommitted = true
                        AppResult.Success(AssetId("old"))
                    }
                }
            queue.cancel(queue.states.value.single().id)
            assertTrue(queue.states.value.isEmpty())
            val fresh =
                async(start = CoroutineStart.UNDISPATCHED) {
                    queue.enqueue(
                        track,
                        candidate,
                        {
                            newRelease.await()
                            AppResult.Success("new")
                        },
                    ) {
                        AppResult.Success(AssetId("new"))
                    }
                }
            val freshId = queue.states.value.single().id
            oldRelease.complete(Unit)
            old.join()
            assertFalse(oldCommitted)
            assertEquals(freshId, queue.states.value.single().id)
            newRelease.complete(Unit)
            assertEquals(AssetId("new"), fresh.await().valueOrNull())
        } finally {
            oldRelease.complete(Unit)
            newRelease.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun callerLeavingDoesNotCancelAcceptedPreparation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val queue = DownloadPreparationQueue(DownloadCommandGate(), scope)
        val release = CompletableDeferred<Unit>()
        var committed = false
        try {
            val caller =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    queue.enqueue(
                        track,
                        candidate,
                        {
                            release.await()
                            AppResult.Success("lease")
                        },
                    ) {
                        committed = true
                        AppResult.Success(AssetId("asset"))
                    }
                }
            caller.cancelAndJoin()
            assertEquals(1, queue.states.value.size)
            release.complete(Unit)
            assertTrue(committed)
            assertTrue(queue.states.value.isEmpty())
        } finally {
            release.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun failedPreparationCanBeRetriedOrDismissed() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val queue = DownloadPreparationQueue(DownloadCommandGate(), scope)
        val failure = AppError(ErrorCode.NETWORK, "fixture", true)
        try {
            queue.enqueue(track, candidate, { AppResult.Failure(failure) }) { _: String ->
                error("Failure must not commit")
            }
            assertEquals(failure, queue.states.value.single().error)
            queue.cancel(queue.states.value.single().id)
            assertTrue(queue.states.value.isEmpty())
            assertEquals(
                AssetId("retry"),
                queue
                    .enqueue(
                        track,
                        candidate,
                        { AppResult.Success("lease") },
                    ) {
                        AppResult.Success(AssetId("retry"))
                    }
                    .valueOrNull(),
            )
            assertTrue(queue.states.value.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
