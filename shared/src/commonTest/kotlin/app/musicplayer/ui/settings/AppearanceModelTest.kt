package app.musicplayer.ui.settings

import app.musicplayer.appearance.*
import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AppearanceModelTest {
    @Test
    fun pendingReadDoesNotOverwriteEarlyChoice() = runTest {
        val preferences = Preferences()
        val model = AppearanceModel(preferences, backgroundScope)
        model.choose(AppTheme.CLASSIC)
        model.choose(AppTheme.LIGHT)
        runCurrent()
        assertFalse(model.state.value.initialized)
        assertTrue(preferences.writes.isEmpty())
        preferences.selection.value = AppTheme.DARK
        runCurrent()
        assertEquals(listOf(AppTheme.LIGHT), preferences.writes)
        assertEquals(AppTheme.LIGHT, model.state.value.theme)
        assertTrue(model.state.value.initialized)
        assertFalse(model.state.value.saving)
        model.close()
    }

    @Test
    fun savingRejectsConcurrentChoiceAndFailureKeepsDurableTheme() = runTest {
        val preferences = Preferences(AppTheme.CLASSIC)
        val gate = CompletableDeferred<Unit>()
        preferences.gate = gate
        preferences.failure = AppError(ErrorCode.DATABASE, "settings.appearance.save", true)
        val model = AppearanceModel(preferences, backgroundScope)
        runCurrent()
        model.choose(AppTheme.LIGHT)
        runCurrent()
        assertTrue(model.state.value.saving)
        model.choose(AppTheme.DARK)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(AppTheme.LIGHT), preferences.writes)
        assertEquals(AppTheme.CLASSIC, model.state.value.theme)
        assertEquals(ErrorCode.DATABASE, model.state.value.error?.code)
        assertFalse(model.state.value.saving)
        model.clearError()
        assertNull(model.state.value.error)
        model.close()
    }

    private class Preferences(initial: AppTheme? = null) : AppearancePreferencesRepository {
        override val selection = MutableStateFlow(initial)
        val writes = mutableListOf<AppTheme>()
        var gate: CompletableDeferred<Unit>? = null
        var failure: AppError? = null

        override suspend fun setTheme(theme: AppTheme): AppResult<Unit> {
            writes += theme
            gate?.await()
            failure?.let {
                return AppResult.Failure(it)
            }
            selection.value = theme
            return AppResult.Success(Unit)
        }
    }
}
