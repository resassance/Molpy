package app.musicplayer.downloads

import app.musicplayer.diagnostics.DiagnosticEvent
import app.musicplayer.model.ErrorCode

internal class DownloadTrace(
    private val operation: String,
    private val source: String,
    private val record: (DiagnosticEvent) -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val started = clock()
    private var transferred = 0L
    private var firstByte = false
    private var lastReport = started
    private var closed = false

    @Synchronized
    fun mark(stage: String, error: ErrorCode? = null, status: Int? = null) {
        if (closed) return
        record(
            DiagnosticEvent(
                operation,
                stage,
                null,
                source,
                "PROGRESSIVE",
                error,
                (clock() - started).coerceAtLeast(0),
                bytesTransferred = transferred,
                httpStatus = status,
            )
        )
    }

    @Synchronized
    fun bytes(count: Int) {
        if (closed || count <= 0) return
        transferred += count
        if (!firstByte) {
            firstByte = true
            lastReport = clock()
            mark("first_byte")
        } else if (clock() - lastReport >= 1000) {
            lastReport = clock()
            mark("transfer")
        }
    }

    @Synchronized
    fun close() {
        mark("closed")
        closed = true
    }
}
