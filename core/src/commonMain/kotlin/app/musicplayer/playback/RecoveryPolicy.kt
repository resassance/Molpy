package app.musicplayer.playback

import app.musicplayer.model.AppError
import app.musicplayer.model.ErrorCode

/** Dispatcher-confined budget. Use a new unique ID only for an explicit user selection or retry. */
class RecoveryPolicy {
    private var activeAttemptId: String? = null
    private var spent = false

    fun resetForUserAttempt(attemptId: String) {
        if (activeAttemptId == attemptId) return
        activeAttemptId = attemptId
        spent = false
    }

    /** Consumes the single fresh-resolve operation; playback callbacks never replenish it. */
    fun shouldResolveAgain(error: AppError, attemptId: String): Boolean {
        if (attemptId != activeAttemptId || spent) return false
        if (error.code != ErrorCode.URL_REJECTED && error.code != ErrorCode.INCOMPATIBLE_RESOURCE) return false
        spent = true
        return true
    }
}
