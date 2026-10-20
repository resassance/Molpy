package app.musicplayer.downloads

import app.musicplayer.model.AppResult
import app.musicplayer.model.ErrorCode
import app.musicplayer.model.errorOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

class CompletionPolicyTest {
    private val policy = CompletionPolicy()
    private val complete = CompletionEvidence(100L, 100L, false, 120_000L, 120_000L, true, true)

    @Test fun exactKnownLengthAndDurationPass() {
        assertComplete(complete)
    }

    @Test fun transportReadabilityAndContiguousBytesAreRequired() {
        for (evidence in listOf(complete.copy(transportComplete = false), complete.copy(readable = false), complete.copy(hasHoles = true))) {
            assertIncomplete(evidence)
        }
    }

    @Test fun knownLengthMustMatchExactlyEvenWithMatchingDuration() {
        for (stored in listOf(99L, 101L, Long.MAX_VALUE)) assertIncomplete(complete.copy(storedBytes = stored))
    }

    @Test fun readableShortMediaFailsDespiteTransportCompletionAndMatchingBytes() {
        assertIncomplete(complete.copy(decodedDurationMs = 10_000L))
    }

    @Test fun twoSecondToleranceIncludesBoundaryForShortMedia() {
        for (decoded in listOf(58_000L, 60_000L, 62_000L)) {
            assertComplete(complete.copy(sourceDurationMs = 60_000, decodedDurationMs = decoded))
        }
        for (decoded in listOf(57_999L, 62_001L)) {
            assertIncomplete(complete.copy(sourceDurationMs = 60_000, decodedDurationMs = decoded))
        }
    }

    @Test fun twoPercentToleranceIncludesBoundaryForLongMedia() {
        for (decoded in listOf(117_600L, 122_400L)) assertComplete(complete.copy(decodedDurationMs = decoded))
        for (decoded in listOf(117_599L, 122_401L)) assertIncomplete(complete.copy(decodedDurationMs = decoded))
    }

    @Test fun fractionalTwoPercentDoesNotRoundUpToAcceptAnotherMillisecond() {
        assertComplete(complete.copy(sourceDurationMs = 100_001L, decodedDurationMs = 102_001L))
        assertIncomplete(complete.copy(sourceDurationMs = 100_001L, decodedDurationMs = 102_002L))
    }

    @Test fun matchingDurationCanProveUnknownLength() {
        assertComplete(complete.copy(expectedBytes = null))
        assertIncomplete(complete.copy(expectedBytes = null, decodedDurationMs = 10_000L))
    }

    @Test fun knownLengthCanProveCompletionWhenDurationIsUnavailable() {
        for (evidence in listOf(complete.copy(sourceDurationMs = null), complete.copy(decodedDurationMs = null), complete.copy(sourceDurationMs = null, decodedDurationMs = null))) {
            assertComplete(evidence)
        }
    }

    @Test fun absentLengthAndIncompleteDurationEvidenceNeverProvesCompletion() {
        for (evidence in listOf(complete.copy(expectedBytes = null, sourceDurationMs = null), complete.copy(expectedBytes = null, decodedDurationMs = null), complete.copy(expectedBytes = null, sourceDurationMs = null, decodedDurationMs = null))) {
            assertIncomplete(evidence)
        }
    }

    @Test fun zeroNegativeOrInvalidEvidenceCannotDescribeCompleteMedia() {
        for (bytes in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertIncomplete(complete.copy(storedBytes = bytes))
            assertIncomplete(complete.copy(expectedBytes = bytes, storedBytes = bytes))
        }
        for (duration in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertIncomplete(complete.copy(sourceDurationMs = duration))
            assertIncomplete(complete.copy(decodedDurationMs = duration))
        }
        assertIncomplete(complete.copy(expectedBytes = -1))
    }

    @Test fun maximumDurationsAndByteCountsDoNotOverflow() {
        assertComplete(complete.copy(expectedBytes = Long.MAX_VALUE, storedBytes = Long.MAX_VALUE, sourceDurationMs = Long.MAX_VALUE, decodedDurationMs = Long.MAX_VALUE))
        assertIncomplete(complete.copy(sourceDurationMs = Long.MAX_VALUE, decodedDurationMs = 1))
        assertIncomplete(complete.copy(sourceDurationMs = 1, decodedDurationMs = Long.MAX_VALUE))
        val tolerance = Long.MAX_VALUE / 50
        assertComplete(complete.copy(sourceDurationMs = Long.MAX_VALUE, decodedDurationMs = Long.MAX_VALUE - tolerance))
        assertIncomplete(complete.copy(sourceDurationMs = Long.MAX_VALUE, decodedDurationMs = Long.MAX_VALUE - tolerance - 1))
    }

    private fun assertComplete(evidence: CompletionEvidence) {
        assertEquals(AppResult.Success(Unit), policy.verify(evidence), "$evidence")
    }

    private fun assertIncomplete(evidence: CompletionEvidence) {
        assertEquals(ErrorCode.INCOMPLETE_MEDIA, policy.verify(evidence).errorOrNull()?.code, "$evidence")
    }
}
