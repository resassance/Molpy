package app.musicplayer.diagnostics

/** In-memory local ring, capped at 1 MiB of encoded records; no file/network/telemetry sink. */
class RedactedDiagnostics {
    private val events = ArrayDeque<DiagnosticEvent>()
    private var retainedBytes = 0

    @Synchronized
    fun record(event: DiagnosticEvent) {
        val safe = redact(event)
        val bytes = render(safe).length + 1 // Redacted fields and JSON keys are ASCII; include newline.
        while (retainedBytes + bytes > MAX_BYTES && events.isNotEmpty()) {
            retainedBytes -= render(events.removeFirst()).length + 1
        }
        events.addLast(safe)
        retainedBytes += bytes
    }

    /** Reapply the same allowlist/redaction at the export boundary. Nothing is sent automatically. */
    @Synchronized
    fun exportRedacted(): String = buildString(retainedBytes) {
        for (event in events) append(render(redact(event))).append('\n')
    }

    private fun redact(event: DiagnosticEvent) = event.copy(
        operationId = field(event.operationId),
        stage = field(event.stage),
        providerVersion = event.providerVersion?.let(::field),
        sourceId = event.sourceId?.let(::field),
        transport = event.transport?.let(::field),
        elapsedMs = event.elapsedMs.coerceAtLeast(0),
    )

    // Refuse the entire field, rather than trying to preserve pieces of an untrusted message.
    private fun field(value: String): String = if (
        value.length !in 1..128 || !IDENTIFIER.matches(value) || SENSITIVE.containsMatchIn(value)
    ) REDACTED else value

    private fun render(event: DiagnosticEvent): String = buildString {
        append("{\"operationId\":").append(quoted(event.operationId))
        append(",\"stage\":").append(quoted(event.stage))
        append(",\"providerVersion\":").append(quoted(event.providerVersion))
        append(",\"sourceId\":").append(quoted(event.sourceId))
        append(",\"transport\":").append(quoted(event.transport))
        append(",\"errorCode\":").append(quoted(event.errorCode?.name))
        append(",\"elapsedMs\":").append(event.elapsedMs).append('}')
    }

    // All non-null values have passed the ASCII identifier allowlist, so JSON escaping is unnecessary.
    private fun quoted(value: String?): String = value?.let { "\"$it\"" } ?: "null"

    private companion object {
        const val MAX_BYTES = 1024 * 1024
        const val REDACTED = "redacted"
        val IDENTIFIER = Regex("[A-Za-z0-9_.-]+")
        val SENSITIVE = Regex("https?|authorization|cookie|password|passwd|secret|signature|headers?|bearer|redirect|token[._-]", RegexOption.IGNORE_CASE)
    }
}
