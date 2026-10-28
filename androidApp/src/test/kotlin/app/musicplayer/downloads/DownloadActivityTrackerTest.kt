package app.musicplayer.downloads

import app.musicplayer.model.*
import org.junit.Assert.*
import org.junit.Test

class DownloadActivityTrackerTest {
    private val id = AssetId("asset")

    @Test
    fun transferWaitsForFirstBytesAndThrottlesProgress() {
        var now = 0L
        val tracker = DownloadActivityTracker { now }
        tracker.native(id, "owner", DownloadState.QUEUED, 0, 4096)
        assertEquals(DownloadPhase.QUEUED, tracker.states.value[id]?.phase)
        val session = tracker.begin(id, "owner", 0, 4096)!!
        assertEquals(DownloadPhase.PREPARING, tracker.states.value[id]?.phase)
        now = 20000
        tracker.bytes(id, session, 1024)
        assertEquals(DownloadPhase.DOWNLOADING, tracker.states.value[id]?.phase)
        assertEquals(1024L, tracker.states.value[id]?.bytes)
        tracker.bytes(id, session, 2048)
        assertEquals(1024L, tracker.states.value[id]?.bytes)
        now += 1000
        tracker.bytes(id, session, 3072)
        assertEquals(3072L, tracker.states.value[id]?.bytes)
    }

    @Test
    fun pausedAndOldAttemptCallbacksCannotReviveProgress() {
        val tracker = DownloadActivityTracker { 0L }
        tracker.native(id, "old", DownloadState.DOWNLOADING, 0, 4096)
        val old = tracker.begin(id, "old", 0, 4096)!!
        tracker.native(id, "old", DownloadState.PAUSED, 1024, 4096)
        tracker.bytes(id, old, 2048)
        assertNull(tracker.states.value[id])
        tracker.native(id, "new", DownloadState.QUEUED, 0, 8192)
        val fresh = tracker.begin(id, "new", 0, 8192)!!
        tracker.bytes(id, old, 4096)
        assertEquals(0L, tracker.states.value[id]?.bytes)
        tracker.bytes(id, fresh, 1024)
        assertEquals(8192L, tracker.states.value[id]?.totalBytes)
    }

    @Test
    fun verificationIsNotReplacedByLateBytesOrOldCompletion() {
        val tracker = DownloadActivityTracker { 0L }
        tracker.native(id, "owner", DownloadState.DOWNLOADING, 0, 4096)
        val session = tracker.begin(id, "owner", 0, 4096)!!
        tracker.verifying(id, "owner", 4096, 4096)
        tracker.bytes(id, session, 4096)
        assertEquals(DownloadPhase.VERIFYING, tracker.states.value[id]?.phase)
        tracker.native(id, "old", DownloadState.AVAILABLE, 4096, 4096)
        assertEquals(DownloadPhase.VERIFYING, tracker.states.value[id]?.phase)
        tracker.native(id, "owner", DownloadState.AVAILABLE, 4096, 4096)
        assertNull(tracker.states.value[id])
    }
}
