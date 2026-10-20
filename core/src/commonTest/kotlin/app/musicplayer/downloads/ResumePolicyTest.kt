package app.musicplayer.downloads

import app.musicplayer.model.SourceRef
import kotlin.test.Test
import kotlin.test.assertEquals

class ResumePolicyTest {
    private val policy = ResumePolicy()
    private val stamp = ResourceStamp(SourceRef("fixture", "track", "one"), "audio-v1", "\"etag-v1\"", 100L)
    private val response = ResumeResponse(206, 40L, 100L, "\"etag-v1\"")

    @Test fun unchangedIdentityWithCorrectPartialResponseResumes() {
        assertEquals(ResumeDecision.RESUME, decide())
    }

    @Test fun changedSourceOrRepresentationRestartsAfterFreshResolve() {
        val changed = listOf(
            stamp.copy(source = stamp.source.copy(provider = "other")),
            stamp.copy(source = stamp.source.copy(kind = "video")),
            stamp.copy(source = stamp.source.copy(id = "two")),
            stamp.copy(representationId = "audio-v2"),
        )
        for (current in changed) assertEquals(ResumeDecision.RESTART, decide(current = current), "$current")
    }

    @Test fun missingWeakMalformedOrChangedValidatorNeverAppends() {
        val unreliable = listOf(null, "", " ", "W/\"etag-v1\"", "w/\"etag-v1\"", "etag-v1", "\"unterminated", "\"bad\nvalue\"", "\"etag-v2\"")
        for (validator in unreliable) {
            assertEquals(ResumeDecision.RESTART, decide(saved = stamp.copy(strongValidator = validator)), "saved $validator")
            assertEquals(ResumeDecision.RESTART, decide(current = stamp.copy(strongValidator = validator)), "current $validator")
            assertEquals(ResumeDecision.RESTART, decide(reply = response.copy(validator = validator)), "response $validator")
        }
    }

    @Test fun equallyWeakOrMissingValidatorsStillDoNotProveIdentity() {
        for (validator in listOf(null, "", "W/\"same\"", "unquoted")) {
            assertEquals(ResumeDecision.RESTART, decide(
                saved = stamp.copy(strongValidator = validator),
                current = stamp.copy(strongValidator = validator),
                reply = response.copy(validator = validator),
            ), "$validator")
        }
    }

    @Test fun unknownOrChangedLengthRestarts() {
        for (length in listOf(null, 99L, 101L)) {
            assertEquals(ResumeDecision.RESTART, decide(saved = stamp.copy(expectedBytes = length)), "saved $length")
            assertEquals(ResumeDecision.RESTART, decide(current = stamp.copy(expectedBytes = length)), "current $length")
            assertEquals(ResumeDecision.RESTART, decide(reply = response.copy(totalBytes = length)), "response $length")
        }
        assertEquals(ResumeDecision.RESTART, decide(saved = stamp.copy(expectedBytes = null), current = stamp.copy(expectedBytes = null), reply = response.copy(totalBytes = null)))
    }

    @Test fun ignoredRangeOrWrongPartialOffsetRestarts() {
        assertEquals(ResumeDecision.RESTART, decide(reply = response.copy(status = 200, rangeStart = null)))
        for (start in listOf(null, 0L, 39L, 41L, 100L, Long.MAX_VALUE)) {
            assertEquals(ResumeDecision.RESTART, decide(reply = response.copy(rangeStart = start)), "$start")
        }
    }

    @Test fun zeroOrAlreadyCompleteOffsetDoesNotAppend() {
        for (offset in listOf(0L, 100L, 101L, Long.MAX_VALUE)) {
            assertEquals(ResumeDecision.RESTART, decide(offset = offset, reply = response.copy(rangeStart = offset)), "$offset")
        }
    }

    @Test fun invalidInputsFailInsteadOfOfferingRecovery() {
        assertEquals(ResumeDecision.FAIL, decide(offset = -1))
        assertEquals(ResumeDecision.FAIL, decide(saved = stamp.copy(representationId = " ")))
        assertEquals(ResumeDecision.FAIL, decide(current = stamp.copy(representationId = "")))
        for (length in listOf(-1L, Long.MIN_VALUE)) {
            assertEquals(ResumeDecision.FAIL, decide(saved = stamp.copy(expectedBytes = length)), "saved $length")
            assertEquals(ResumeDecision.FAIL, decide(current = stamp.copy(expectedBytes = length)), "current $length")
            assertEquals(ResumeDecision.FAIL, decide(reply = response.copy(totalBytes = length)), "response $length")
            assertEquals(ResumeDecision.FAIL, decide(reply = response.copy(rangeStart = length)), "range $length")
        }
    }

    @Test fun zeroLengthCannotSupportPartialAppend() {
        assertEquals(ResumeDecision.RESTART, decide(saved = stamp.copy(expectedBytes = 0), current = stamp.copy(expectedBytes = 0), offset = 0, reply = response.copy(rangeStart = 0, totalBytes = 0)))
    }

    @Test fun unsuccessfulOrUnsupportedStatusFails() {
        for (status in listOf(-1, 0, 99, 201, 204, 301, 401, 403, 404, 416, 500, 600)) {
            assertEquals(ResumeDecision.FAIL, decide(reply = response.copy(status = status)), "$status")
        }
    }

    @Test fun maximumLengthAndOffsetRemainSafeWithoutAddition() {
        val largest = stamp.copy(expectedBytes = Long.MAX_VALUE)
        assertEquals(ResumeDecision.RESUME, decide(saved = largest, current = largest, offset = Long.MAX_VALUE - 1, reply = response.copy(rangeStart = Long.MAX_VALUE - 1, totalBytes = Long.MAX_VALUE)))
    }

    private fun decide(saved: ResourceStamp = stamp, current: ResourceStamp = stamp, offset: Long = 40L, reply: ResumeResponse = response) =
        policy.decide(saved, current, offset, reply)
}
