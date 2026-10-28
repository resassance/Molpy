package app.musicplayer.downloads

import app.musicplayer.model.*

enum class DownloadPhase {
    PREPARING,
    QUEUED,
    DOWNLOADING,
    VERIFYING,
}

data class DownloadPreparation(
    val id: String,
    val track: Track,
    val candidateId: CandidateId,
    val error: AppError? = null,
)

data class DownloadActivity(
    val phase: DownloadPhase,
    val bytes: Long,
    val totalBytes: Long?,
    val attempt: String,
)
