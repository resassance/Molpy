package app.musicplayer.ui.downloads

import app.musicplayer.downloads.*
import app.musicplayer.model.*
import app.musicplayer.ui.*
import app.musicplayer.ui.components.*
import kotlin.test.*
import kotlinx.coroutines.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DownloadsModelTest {
    private fun asset(): OfflineAsset {
        val track = sampleTrack()
        return OfflineAsset(
            AssetId("asset"),
            track.id,
            track,
            track.candidates.single().id,
            track.candidates.single().source,
            Representation("f", "audio/test", "test", 100, 60000, null),
            "local",
            40,
            DownloadState.DOWNLOADING,
        )
    }

    @Test
    fun storageFailureRetainsNormalizedTransferAndSettlesLoading() = runTest {
        val saved = asset()
        val downloads = PresentationDownloads().apply { states.value = listOf(saved) }
        val model =
            DownloadsModel(
                downloads,
                { throw IllegalStateException("private storage detail") },
                backgroundScope,
            )
        runCurrent()
        assertEquals(listOf(saved), model.state.value.assets)
        assertEquals(ErrorCode.DATABASE, model.state.value.error?.code)
        assertFalse(model.state.value.loading)
        model.close()
    }

    @Test
    fun commandFailureIsAttachedToAssetAndDoesNotPublishAvailability() = runTest {
        val saved = asset()
        val downloads =
            object : PresentationDownloads() {
                    override suspend fun resume(id: AssetId): AppResult<Unit> =
                        AppResult.Failure(AppError(ErrorCode.STORAGE_FULL, "fixture", true))
                }
                .apply { states.value = listOf(saved.copy(state = DownloadState.PAUSED)) }
        val model =
            DownloadsModel(
                downloads,
                { StorageUsageSnapshot(40, mapOf(saved.id to 40)) },
                backgroundScope,
            )
        runCurrent()
        model.resume(saved.id)
        runCurrent()
        assertEquals(ErrorCode.STORAGE_FULL, model.state.value.failures[saved.id]?.code)
        assertEquals(DownloadState.PAUSED, model.state.value.assets.single().state)
        assertEquals(40L, model.state.value.usage.totalBytes)
        model.close()
    }

    @Test
    fun unknownLengthHasNoInventedPercentageAndActualNonFirstCandidateWins() {
        val saved = asset()
        assertEquals(0.4f, downloadProgress(saved))
        assertNull(
            downloadProgress(
                saved.copy(representation = saved.representation.copy(expectedBytes = null))
            )
        )
        val alternate =
            saved.trackSnapshot.candidates
                .single()
                .copy(id = CandidateId("recorded"), source = SourceRef("test", "video", "recorded"))
        val track =
            saved.trackSnapshot.copy(candidates = saved.trackSnapshot.candidates + alternate)
        val snapshot =
            PlaybackSnapshot(
                PlayerStatus.PLAYING,
                QueueEntry(QueueEntryId("entry"), track.id),
                track,
                candidateId = alternate.id,
            )
        assertEquals(alternate, selectDownload(track, snapshot)?.candidate)
        assertNull(
            selectDownload(
                track.copy(
                    candidates =
                        track.candidates.map { it.copy(confidence = MatchConfidence.TENTATIVE) }
                ),
                PlaybackSnapshot(),
            )
        )
    }
}
