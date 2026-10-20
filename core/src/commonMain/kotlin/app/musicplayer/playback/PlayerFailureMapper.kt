package app.musicplayer.playback

import app.musicplayer.model.*

/** Media3 1.11.1 codes and the typed HTTP cause's status. No message/body inference. */
fun mapPlayerFailure(code: Int, httpStatus: Int?): AppError {
    val category = when {
        httpStatus == 407 -> ErrorCode.PROXY_AUTH
        httpStatus == 401 -> ErrorCode.LOGIN_REQUIRED
        httpStatus == 429 -> ErrorCode.RATE_LIMIT
        httpStatus == 451 || code == -106 -> ErrorCode.REGION_RESTRICTED
        code == -102 || code == -103 -> ErrorCode.LOGIN_REQUIRED
        httpStatus in listOf(403, 404, 410) -> ErrorCode.URL_REJECTED
        code in 2000..2999 -> ErrorCode.NETWORK
        code in 3000..4999 -> ErrorCode.INCOMPATIBLE_RESOURCE
        else -> ErrorCode.UNKNOWN
    }
    return AppError(category, "player.callback", category in setOf(ErrorCode.NETWORK, ErrorCode.URL_REJECTED,
        ErrorCode.INCOMPATIBLE_RESOURCE, ErrorCode.RATE_LIMIT))
}
