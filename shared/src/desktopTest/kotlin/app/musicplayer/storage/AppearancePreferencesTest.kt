package app.musicplayer.storage

import app.musicplayer.appearance.*
import app.musicplayer.diagnostics.DiagnosticEvent
import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AppearancePreferencesTest {
    @Test
    fun cancelledCallerDoesNotWriteOrReportDatabaseFailure() = runBlocking {
        val library = TestLibrary()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = java.util.concurrent.CopyOnWriteArrayList<DiagnosticEvent>()
        try {
            library.open()
            val preferences =
                RoomAppearancePreferencesRepository(library.db, owner) { events += it }
            withTimeout(5000) { preferences.selection.first { it != null } }
            var cancellationPropagated = false
            val caller = launch {
                currentCoroutineContext().cancel()
                try {
                    preferences.setTheme(AppTheme.LIGHT)
                } catch (_: CancellationException) {
                    cancellationPropagated = true
                }
            }
            caller.join()
            assertTrue(cancellationPropagated)
            assertEquals(AppTheme.DARK, preferences.selection.value)
            assertNull(library.db.dao().setting("ui.theme"))
            assertTrue(events.isEmpty())
        } finally {
            owner.cancel()
            library.close()
        }
    }

    @Test
    fun cancelledOwnerDoesNotInitializeOrReportReadFailure() = runTest {
        val library = TestLibrary()
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val events = mutableListOf<DiagnosticEvent>()
        try {
            val preferences =
                RoomAppearancePreferencesRepository(library.db, owner) { events += it }
            owner.cancel()
            runCurrent()
            assertNull(preferences.selection.value)
            assertTrue(events.isEmpty())
        } finally {
            owner.cancel()
            library.close()
        }
    }

    @Test
    fun newDatabaseStartsDarkAndSelectionSurvivesReopen() = runBlocking {
        val library = TestLibrary()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            library.open()
            val preferences = RoomAppearancePreferencesRepository(library.db, owner)
            assertEquals(
                AppTheme.DARK,
                withTimeout(5000) { preferences.selection.first { it != null } },
            )
            assertIs<AppResult.Success<Unit>>(preferences.setTheme(AppTheme.LIGHT))
            library.reopen()
            val reopened = RoomAppearancePreferencesRepository(library.db, owner)
            assertEquals(
                AppTheme.LIGHT,
                withTimeout(5000) { reopened.selection.first { it != null } },
            )
        } finally {
            owner.cancel()
            library.close()
        }
    }

    @Test
    fun failedWriteKeepsLastTheme() = runBlocking {
        val library = TestLibrary()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            library.open()
            val preferences = RoomAppearancePreferencesRepository(library.db, owner)
            assertIs<AppResult.Success<Unit>>(preferences.setTheme(AppTheme.CLASSIC))
            library.db.close()
            val result = assertIs<AppResult.Failure>(preferences.setTheme(AppTheme.LIGHT))
            assertEquals(ErrorCode.DATABASE, result.error.code)
            assertEquals(AppTheme.CLASSIC, preferences.selection.value)
        } finally {
            owner.cancel()
            library.close()
        }
    }

    @Test
    fun invalidValueUsesDarkWithoutOverwritingStorage() = runBlocking {
        val library = TestLibrary()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = mutableListOf<DiagnosticEvent>()
        try {
            library.open()
            library.db.dao().putSetting(SettingsRecord("ui.theme", "SYSTEM"))
            val preferences =
                RoomAppearancePreferencesRepository(library.db, owner) {
                    synchronized(events) { events += it }
                }
            assertEquals(
                AppTheme.DARK,
                withTimeout(5000) { preferences.selection.first { it != null } },
            )
            assertEquals("SYSTEM", library.db.dao().setting("ui.theme")?.value)
            assertTrue(synchronized(events) { events.any { it.errorCode == ErrorCode.DATABASE } })
        } finally {
            owner.cancel()
            library.close()
        }
    }

    @Test
    fun readFailureInitializesDarkWithoutWritingFallback() = runBlocking {
        val library = TestLibrary()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            library.open()
            library.db.close()
            val preferences = RoomAppearancePreferencesRepository(library.db, owner)
            assertEquals(
                AppTheme.DARK,
                withTimeout(5000) { preferences.selection.first { it != null } },
            )
            library.reopen()
            assertNull(library.db.dao().setting("ui.theme"))
        } finally {
            owner.cancel()
            library.close()
        }
    }
}
