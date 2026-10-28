package app.musicplayer.downloads

import app.musicplayer.diagnostics.DiagnosticEvent
import org.junit.Assert.*
import org.junit.Test

class DownloadTraceTest {
    @Test
    fun separatesPreparationFirstByteAndTransferTimeWithoutLoggingPerRead() {
        var now = 100L
        val events = mutableListOf<DiagnosticEvent>()
        val trace = DownloadTrace("download.transfer", "recording", events::add) { now }
        trace.mark("resolve")
        now += 5000
        trace.mark("connect")
        now += 15000
        trace.mark("response", status = 206)
        trace.bytes(1024)
        repeat(100) { trace.bytes(1024) }
        now += 1000
        trace.bytes(2048)
        trace.close()
        trace.close()
        assertEquals(
            listOf("resolve", "connect", "response", "first_byte", "transfer", "closed"),
            events.map { it.stage },
        )
        assertEquals(20_000L, events.single { it.stage == "first_byte" }.elapsedMs)
        assertEquals(21_000L, events.single { it.stage == "transfer" }.elapsedMs)
        assertEquals(105472L, events.last().bytesTransferred)
        assertEquals(206, events.single { it.stage == "response" }.httpStatus)
        assertTrue(
            events.all { it.sourceId == "recording" && it.operationId == "download.transfer" }
        )
    }
}
