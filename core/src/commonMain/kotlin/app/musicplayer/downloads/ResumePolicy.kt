package app.musicplayer.downloads

import app.musicplayer.model.SourceRef

data class ResourceStamp(
    val source: SourceRef,
    val representationId: String,
    val strongValidator: String?,
    val expectedBytes: Long?,
)

data class ResumeResponse(
    val status: Int,
    val rangeStart: Long?,
    val totalBytes: Long?,
    val validator: String?,
)

enum class ResumeDecision { RESUME, RESTART, FAIL }

/** A restart discards old bytes; FAIL leaves invalid inputs or unsuccessful responses to the caller. */
class ResumePolicy {
    fun decide(saved: ResourceStamp, current: ResourceStamp, offset: Long, response: ResumeResponse): ResumeDecision {
        if (offset < 0 || saved.representationId.isBlank() || current.representationId.isBlank() ||
            saved.expectedBytes?.let { it < 0 } == true || current.expectedBytes?.let { it < 0 } == true ||
            response.rangeStart?.let { it < 0 } == true || response.totalBytes?.let { it < 0 } == true
        ) return ResumeDecision.FAIL

        if (response.status == 200) return ResumeDecision.RESTART
        // Authentication/expiry recovery and fresh resolution belong to the downloader, never append.
        if (response.status != 206) return ResumeDecision.FAIL

        if (saved.source != current.source || saved.representationId != current.representationId ||
            !saved.strongValidator.isStrongValidator() || saved.strongValidator != current.strongValidator ||
            saved.strongValidator != response.validator
        ) return ResumeDecision.RESTART

        val total = saved.expectedBytes ?: return ResumeDecision.RESTART
        if (total <= 0 || current.expectedBytes != total || response.totalBytes != total ||
            offset <= 0 || offset >= total || response.rangeStart != offset
        ) return ResumeDecision.RESTART

        return ResumeDecision.RESUME
    }

    /** HTTP strong ETag: a quoted opaque tag, without the W/ prefix or control characters. */
    private fun String?.isStrongValidator(): Boolean {
        if (this == null || length < 2 || first() != '"' || last() != '"') return false
        return substring(1, lastIndex).all { it == '!' || it in '#'..'~' || it in '\u0080'..'\u00ff' }
    }
}
