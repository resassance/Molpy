package app.musicplayer.diagnostics

import app.musicplayer.model.ErrorCode
import kotlin.test.*

class DiagnosticsTest {
    private fun event(id: String = "playback.resolve") =
        DiagnosticEvent(
            id,
            "expiry",
            "innertubex-0.10.0",
            "source_123",
            "PROGRESSIVE",
            ErrorCode.URL_REJECTED,
            123,
        )

    @Test
    fun preservesOnlyStructuredDiagnosticFieldsAndExportsWithoutSending() {
        val diagnostics = RedactedDiagnostics()
        diagnostics.record(event())
        val exported = diagnostics.exportRedacted()
        assertTrue(exported.contains("\"operationId\":\"playback.resolve\""))
        assertTrue(exported.contains("\"stage\":\"expiry\""))
        assertTrue(exported.contains("\"providerVersion\":\"innertubex-0.10.0\""))
        assertTrue(exported.contains("\"sourceId\":\"source_123\""))
        assertTrue(exported.contains("\"transport\":\"PROGRESSIVE\""))
        assertTrue(exported.contains("\"errorCode\":\"URL_REJECTED\""))
        assertTrue(exported.contains("\"elapsedMs\":123"))
        assertEquals(exported, diagnostics.exportRedacted())
    }

    @Test
    fun maliciousRedirectUrlsTokensAuthHeadersCookiesPasswordsAndBodiesCannotEscapeAnyField() {
        val attacks =
            listOf(
                "https://cdn.invalid/redirect?signature=test-secret",
                "//cdn.invalid/redirect?token=test-secret",
                "https%3A%2F%2Fcdn.invalid%2Fredirect%3Ftoken%3Dtest-secret",
                "Authorization: Bearer test-secret",
                "Proxy-Authorization: Basic test-password",
                "Cookie: session=test-secret",
                "Set-Cookie: session=test-secret\r\nAuthorization: test-password",
                "password=test-password",
                "token:test-secret",
                "headers.test-secret",
                "{\"providerBody\":\"test-secret\"}",
                "stage\nforged-event test-secret",
            )
        val diagnostics = RedactedDiagnostics()
        for (attack in attacks) {
            diagnostics.record(
                DiagnosticEvent(attack, attack, attack, attack, attack, ErrorCode.URL_REJECTED, -1)
            )
        }
        val exported = diagnostics.exportRedacted()
        assertFalse(exported.contains("test-secret"))
        assertFalse(exported.contains("test-password"))
        assertFalse(exported.contains("signature="))
        assertFalse(exported.contains("cdn.invalid"))
        assertFalse(exported.contains("providerBody"))
        assertFalse(exported.contains("forged-event"))
        assertEquals(attacks.size, exported.lines().count { it.isNotEmpty() })
        assertTrue(
            exported.lines().filter { it.isNotEmpty() }.all { it.contains("\"elapsedMs\":0") }
        )
    }

    @Test
    fun localRingAndExportStayWithinOneMiBAndEvictOldestRecords() {
        val diagnostics = RedactedDiagnostics()
        diagnostics.record(event("oldest"))
        repeat(10_000) { diagnostics.record(event("operation.$it")) }
        val exported = diagnostics.exportRedacted()
        assertTrue(exported.toByteArray(Charsets.UTF_8).size <= 1024 * 1024)
        assertFalse(exported.contains("\"oldest\""))
        assertTrue(exported.contains("\"operation.9999\""))
    }

    @Test
    fun oversizedFieldsAreRedactedAsAWholeBeforeTheyCanEnterTheRing() {
        val diagnostics = RedactedDiagnostics()
        diagnostics.record(event("x".repeat(2 * 1024 * 1024) + "test-secret"))
        val exported = diagnostics.exportRedacted()
        assertTrue(exported.length < 2048)
        assertFalse(exported.contains("test-secret"))
    }

    @Test
    fun typedFiltersAndCountsArePreservedWhileOtherValuesAreDiscarded() {
        val diagnostics = RedactedDiagnostics()
        for (filter in listOf("ALL", "TRACKS", "ARTISTS", "ALBUMS", "PLAYLISTS")) diagnostics
            .record(event().copy(filter = filter, itemCount = 0, pageCount = Int.MAX_VALUE))
        for (filter in
            listOf(
                "secret-query-sentinel",
                "tracks",
                " TRACKS",
                "TRACKS\n",
                "https://host.invalid?token=sentinel",
            )) diagnostics.record(event().copy(filter = filter, itemCount = -1, pageCount = -2))
        val lines = diagnostics.exportRedacted().trim().lines()
        assertEquals(10, lines.size)
        for ((index, filter) in
            listOf("ALL", "TRACKS", "ARTISTS", "ALBUMS", "PLAYLISTS").withIndex()) {
            assertTrue(lines[index].contains("\"filter\":\"$filter\""))
            assertTrue(lines[index].contains("\"itemCount\":0"))
            assertTrue(lines[index].contains("\"pageCount\":2147483647"))
        }
        assertTrue(
            lines.drop(5).all {
                it.contains("\"filter\":null") &&
                    it.contains("\"itemCount\":null") &&
                    it.contains("\"pageCount\":null")
            }
        )
        assertFalse(diagnostics.exportRedacted().contains("sentinel"))
    }

    @Test
    fun transferCountersAndHttpStatusesAreValidatedAtExport() {
        val diagnostics = RedactedDiagnostics()
        diagnostics.record(event().copy(bytesTransferred = 819200, httpStatus = 206))
        diagnostics.record(event().copy(bytesTransferred = -1, httpStatus = 999))
        val lines = diagnostics.exportRedacted().trim().lines()
        assertTrue(lines[0].contains("\"bytesTransferred\":819200"))
        assertTrue(lines[0].contains("\"httpStatus\":206"))
        assertTrue(lines[1].contains("\"bytesTransferred\":null"))
        assertTrue(lines[1].contains("\"httpStatus\":null"))
    }

    @Test
    fun sharedDiagnosticsKeepNewestWholeRecordsWithinBinderBudget() {
        val diagnostics = RedactedDiagnostics()
        repeat(3000) { diagnostics.record(event("event.$it")) }
        val text = diagnostics.exportForSharing()
        assertTrue(text.length <= 64 * 1024)
        assertTrue(text.trim().lines().all { it.startsWith("{") && it.endsWith("}") })
        assertTrue(text.contains("event.2999"))
    }
}
