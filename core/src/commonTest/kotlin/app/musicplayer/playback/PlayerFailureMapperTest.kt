package app.musicplayer.playback
import app.musicplayer.model.*
import kotlin.test.*
class PlayerFailureMapperTest {
    @Test fun httpCategoriesExcludeAuthenticationAndRateLimitFromRecovery() {
        mapOf(407 to ErrorCode.PROXY_AUTH, 401 to ErrorCode.LOGIN_REQUIRED, 429 to ErrorCode.RATE_LIMIT,
            451 to ErrorCode.REGION_RESTRICTED, 403 to ErrorCode.URL_REJECTED, 404 to ErrorCode.URL_REJECTED,
            410 to ErrorCode.URL_REJECTED, 500 to ErrorCode.NETWORK).forEach { (status, expected) ->
            assertEquals(expected, mapPlayerFailure(2004, status).code, "HTTP $status")
        }
    }
    @Test fun missingHttpCauseIsNetworkAndNativeRestrictionCodesStayDistinct() {
        assertEquals(ErrorCode.NETWORK, mapPlayerFailure(2004, null).code)
        assertEquals(ErrorCode.NETWORK, mapPlayerFailure(2001, null).code)
        assertEquals(ErrorCode.INCOMPATIBLE_RESOURCE, mapPlayerFailure(3003, null).code)
        assertEquals(ErrorCode.REGION_RESTRICTED, mapPlayerFailure(-106, null).code)
        assertEquals(ErrorCode.LOGIN_REQUIRED, mapPlayerFailure(-102, null).code)
    }
}
