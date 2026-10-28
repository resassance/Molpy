package app.musicplayer.ui.downloads

import app.musicplayer.downloads.*
import kotlin.test.*

class DownloadRateEstimatorTest {
    private fun transfer(bytes: Long, total: Long? = 3072, attempt: String = "first") =
        DownloadActivity(DownloadPhase.DOWNLOADING, bytes, total, attempt)

    @Test
    fun estimatesOnlyMeasuredTransferTime() {
        val rate = DownloadRateEstimator()
        assertNull(rate.update(transfer(0), 20000).bytesPerSecond)
        val reading = rate.update(transfer(1024), 21000)
        assertEquals(1024L, reading.bytesPerSecond)
        assertEquals(2L, reading.remainingSeconds)
    }

    @Test
    fun unknownTotalNeverInventsRemainingTime() {
        val rate = DownloadRateEstimator()
        rate.update(transfer(0, null), 0)
        val reading = rate.update(transfer(1024, null), 1000)
        assertEquals(1024L, reading.bytesPerSecond)
        assertNull(reading.remainingSeconds)
    }

    @Test
    fun stalledTransferClearsSpeedAndRemainingTime() {
        val rate = DownloadRateEstimator()
        rate.update(transfer(0), 0)
        rate.update(transfer(1024), 1000)
        val reading = rate.update(transfer(1024), 6000)
        assertNull(reading.bytesPerSecond)
        assertNull(reading.remainingSeconds)
        assertTrue(reading.waitingForData)
        assertNull(rate.update(transfer(2048), 7000).bytesPerSecond)
        assertEquals(1024L, rate.update(transfer(3072), 8000).bytesPerSecond)
    }

    @Test
    fun pauseAttemptChangeAndCounterResetDiscardEarlierRate() {
        for (reset in
            listOf<DownloadActivity?>(null, transfer(1024, attempt = "next"), transfer(0))) {
            val rate = DownloadRateEstimator()
            rate.update(transfer(0), 0)
            rate.update(transfer(1024), 1000)
            assertNull(rate.update(reset, 2000).bytesPerSecond)
            assertNull(rate.update(transfer(1536), 2100).bytesPerSecond)
        }
    }

    @Test
    fun verificationDoesNotDisplayTransferSpeedOrEta() {
        val rate = DownloadRateEstimator()
        rate.update(transfer(0), 0)
        rate.update(transfer(1024), 1000)
        val reading = rate.update(transfer(3072).copy(phase = DownloadPhase.VERIFYING), 2000)
        assertNull(reading.bytesPerSecond)
        assertNull(reading.remainingSeconds)
        assertFalse(reading.waitingForData)
    }
}
