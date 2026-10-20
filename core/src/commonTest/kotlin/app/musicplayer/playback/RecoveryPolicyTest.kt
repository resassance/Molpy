package app.musicplayer.playback

import app.musicplayer.model.*
import kotlin.test.*

class RecoveryPolicyTest {
    @Test fun initialResolveHasAtMostOneFreshResolveAcrossEngineAndResolverStages() {
        val policy = RecoveryPolicy()
        policy.resetForUserAttempt("selection-1")
        var resolvesIncludingInitial = 1
        if (policy.shouldResolveAgain(error(ErrorCode.URL_REJECTED, "player.callback"), "selection-1")) {
            resolvesIncludingInitial++
        }
        // The owner must not reset this policy on BUFFERING/PLAYING; callback wiring is an integration concern.
        assertFalse(policy.shouldResolveAgain(error(ErrorCode.URL_REJECTED, "playback.resolve"), "selection-1"))
        assertFalse(policy.shouldResolveAgain(error(ErrorCode.INCOMPATIBLE_RESOURCE, "player.callback"), "selection-1"))
        assertEquals(2, resolvesIncludingInitial)
    }

    @Test fun disappearedFormatAndRejectedUrlShareOneBudget() {
        val policy = RecoveryPolicy()
        policy.resetForUserAttempt("format")
        assertTrue(policy.shouldResolveAgain(error(ErrorCode.INCOMPATIBLE_RESOURCE), "format"))
        assertFalse(policy.shouldResolveAgain(error(ErrorCode.URL_REJECTED), "format"))
    }

    @Test fun onlyExplicitNewUserAttemptRestoresBudgetAndLateErrorsDoNotConsumeIt() {
        val policy = RecoveryPolicy()
        assertFalse(policy.shouldResolveAgain(error(ErrorCode.URL_REJECTED), "unregistered"))
        policy.resetForUserAttempt("old")
        assertTrue(policy.shouldResolveAgain(error(ErrorCode.URL_REJECTED), "old"))
        policy.resetForUserAttempt("old")
        assertFalse(policy.shouldResolveAgain(error(ErrorCode.URL_REJECTED), "old"))
        policy.resetForUserAttempt("new")
        assertFalse(policy.shouldResolveAgain(error(ErrorCode.URL_REJECTED), "old"))
        assertTrue(policy.shouldResolveAgain(error(ErrorCode.URL_REJECTED), "new"))
        assertFalse(policy.shouldResolveAgain(error(ErrorCode.URL_REJECTED), "new"))
    }

    @Test fun proxy407LoginRegionRateLimitAndNetworkNeverSpendExpiredUrlBudget() {
        for (code in ErrorCode.entries.filterNot { it == ErrorCode.URL_REJECTED || it == ErrorCode.INCOMPATIBLE_RESOURCE }) {
            val policy = RecoveryPolicy()
            policy.resetForUserAttempt("attempt")
            assertFalse(policy.shouldResolveAgain(error(code), "attempt"), code.name)
            assertTrue(policy.shouldResolveAgain(error(ErrorCode.URL_REJECTED), "attempt"), code.name)
        }
    }

    private fun error(code: ErrorCode, stage: String = "player.callback") = AppError(code, stage, true)
}
