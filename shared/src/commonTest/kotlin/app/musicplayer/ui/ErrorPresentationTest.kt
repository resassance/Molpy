package app.musicplayer.ui
import app.musicplayer.model.*
import kotlin.test.*
class ErrorPresentationTest {
    @Test fun categoryActionsReachMatchingCallbacksForCommandAndLyricsSurfaces() {
        val invoked = mutableListOf<String>()
        val cases = listOf(ErrorCode.STORAGE_FULL to "storage", ErrorCode.PROXY_AUTH to "network",
            ErrorCode.URL_REJECTED to "retry", ErrorCode.SOURCE_UNAVAILABLE to "next")
        cases.forEach { (code, expected) ->
            invoked.clear()
            performErrorAction(present(AppError(code, "secret details", true)).primaryAction,
                retry = { invoked += "retry" }, next = { invoked += "next" },
                editNetwork = { invoked += "network" }, manageStorage = { invoked += "storage" })
            assertEquals(listOf(expected), invoked)
        }
    }
    @Test fun actionsMatchFailureCategoryAndMessagesNeverExposeOperation() {
        val expected = mapOf(ErrorCode.PROXY_AUTH to ErrorAction.EDIT_NETWORK, ErrorCode.PROXY_UNSUPPORTED to ErrorAction.EDIT_NETWORK,
            ErrorCode.INVALID_CONFIG to ErrorAction.EDIT_NETWORK, ErrorCode.URL_REJECTED to ErrorAction.RETRY,
            ErrorCode.NETWORK to ErrorAction.RETRY, ErrorCode.STORAGE_FULL to ErrorAction.MANAGE_STORAGE,
            ErrorCode.SOURCE_UNAVAILABLE to ErrorAction.NEXT, ErrorCode.REGION_RESTRICTED to ErrorAction.NEXT,
            ErrorCode.LOGIN_REQUIRED to ErrorAction.NEXT, ErrorCode.UNSUPPORTED_TRANSPORT to ErrorAction.NEXT,
            ErrorCode.PROVIDER_CHANGED to ErrorAction.NONE)
        expected.forEach { (code, action) ->
            val result = present(AppError(code, "https://private.test/?password=secret", true))
            assertEquals(action, result.primaryAction, code.name)
            assertFalse(result.message.contains("https")); assertFalse(result.message.contains("secret")); assertTrue(result.message.any { it in 'А'..'я' })
        }
        assertEquals(ErrorAction.NONE, present(AppError(ErrorCode.UNKNOWN, "x", false)).primaryAction)
    }
}
