package app.musicplayer.diagnostics

import app.musicplayer.model.ErrorCode

/** Application-owned fields only: never pass Throwable messages, provider bodies or request objects. */
data class DiagnosticEvent(
    val operationId: String,
    val stage: String,
    val providerVersion: String?,
    val sourceId: String?,
    val transport: String?,
    val errorCode: ErrorCode?,
    val elapsedMs: Long,
)
