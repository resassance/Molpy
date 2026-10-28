package app.musicplayer.ui.downloads

import app.musicplayer.downloads.*
import app.musicplayer.model.*
import app.musicplayer.ui.*
import kotlin.test.*
import kotlinx.coroutines.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DownloadProgressFlowTest {
    private val track = sampleTrack()
    private val candidate = track.candidates.first()

    private fun asset() =
        OfflineAsset(
            AssetId("asset"),
            track.id,
            track,
            candidate.id,
            candidate.source,
            Representation("audio", "audio/mp4", "aac", null, 60000, null),
            "key",
            0,
            DownloadState.DOWNLOADING,
        )

    @Test
    fun preparationIsVisibleWithoutAnAssetAndReplacedWithoutDuplicateRows() = runTest {
        val port = PresentationDownloads()
        val prep = DownloadPreparation("pending", track, candidate.id)
        port.preparations.value = listOf(prep)
        val model = DownloadsModel(port, { StorageUsageSnapshot(0, emptyMap()) }, backgroundScope)
        runCurrent()
        assertEquals(listOf(prep), visiblePreparations(model.state.value))
        assertTrue(model.state.value.hasDownload(track.id))
        port.states.value = listOf(asset())
        runCurrent()
        assertTrue(visiblePreparations(model.state.value).isEmpty())
        assertEquals(1, visibleAssets(model.state.value).size)
        model.close()
    }

    @Test
    fun retryPreparationReplacesFailedAssetUntilHandoff() {
        val saved = asset().copy(state = DownloadState.FAILED)
        val prep = DownloadPreparation("retry", track, candidate.id)
        val state = DownloadsState(assets = listOf(saved), preparations = listOf(prep))
        assertEquals(listOf(prep), visiblePreparations(state))
        assertTrue(visibleAssets(state).isEmpty())
    }

    @Test
    fun measuredRateGoesStaleAndPauseClearsIt() = runTest {
        val port = PresentationDownloads()
        val saved = asset()
        port.states.value = listOf(saved)
        port.activity.value =
            mapOf(saved.id to DownloadActivity(DownloadPhase.DOWNLOADING, 0, 3072, "first"))
        val model =
            DownloadsModel(
                port,
                { StorageUsageSnapshot(0, emptyMap()) },
                backgroundScope,
                clock = { testScheduler.currentTime },
            )
        runCurrent()
        advanceTimeBy(1000)
        port.activity.value =
            mapOf(saved.id to DownloadActivity(DownloadPhase.DOWNLOADING, 1024, 3072, "first"))
        runCurrent()
        assertEquals(1024L, model.state.value.rates[saved.id]?.bytesPerSecond)
        assertEquals(2L, model.state.value.rates[saved.id]?.remainingSeconds)
        advanceTimeBy(5001)
        runCurrent()
        assertTrue(model.state.value.rates.getValue(saved.id).waitingForData)
        port.states.value = listOf(saved.copy(state = DownloadState.PAUSED))
        runCurrent()
        assertFalse(saved.id in model.state.value.rates)
        model.close()
    }

    @Test
    fun probedTotalIsUsedButVerificationAndFailureNeverShowTransferProgress() {
        val saved = asset().copy(bytes = 1024)
        val activity = DownloadActivity(DownloadPhase.DOWNLOADING, 1024, 4096, "attempt")
        val status = downloadStatus(saved, activity, DownloadRate(1024, 3))
        assertEquals(0.25f, status.progress)
        assertTrue(status.label.contains("25%"))
        assertTrue(status.label.contains("1 КБ/с"))
        assertTrue(status.label.contains("около 3 с"))
        val verifying =
            downloadStatus(
                saved,
                activity.copy(phase = DownloadPhase.VERIFYING),
                DownloadRate(1024, 3),
            )
        assertNull(verifying.progress)
        assertTrue(verifying.indeterminate)
        assertFalse(verifying.label.contains("/с"))
        val failed =
            downloadStatus(
                saved.copy(state = DownloadState.FAILED),
                activity.copy(phase = DownloadPhase.VERIFYING),
                DownloadRate(),
            )
        assertFalse(failed.indeterminate)
        assertNull(failed.progress)
    }

    @Test
    fun preparationCommandFailureIsNormalizedWithoutChangingOfflineAssets() = runTest {
        val saved = asset().copy(state = DownloadState.AVAILABLE)
        val port =
            object : PresentationDownloads() {
                    override suspend fun cancelPreparation(id: String): AppResult<Unit> =
                        throw IllegalStateException("private upstream error")
                }
                .apply { states.value = listOf(saved) }
        val model = DownloadsModel(port, { StorageUsageSnapshot(0, emptyMap()) }, backgroundScope)
        runCurrent()
        val result = model.cancelPreparation("pending")
        assertEquals(ErrorCode.UNKNOWN, result.errorOrNull()?.code)
        assertEquals(listOf(saved), model.state.value.assets)
        assertEquals(ErrorCode.UNKNOWN, model.state.value.error?.code)
        model.close()
    }
}
