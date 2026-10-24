package app.musicplayer.ui.settings

import app.musicplayer.appearance.*
import app.musicplayer.model.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AppearanceWiringTest {
    @Test
    fun appearanceStartsAfterPreferenceReadWithoutWrongThemeFrame() = runTest {
        val preferences = Preferences(null)
        val model = AppearanceModel(preferences, backgroundScope)
        runCurrent()
        assertFalse(model.state.value.initialized)
        assertTrue(preferences.writes.isEmpty())
        preferences.selection.value = AppTheme.CLASSIC
        runCurrent()
        assertTrue(model.state.value.initialized)
        assertEquals(AppTheme.CLASSIC, model.state.value.theme)
        assertTrue(preferences.writes.isEmpty())
        model.close()
        preferences.selection.value = AppTheme.LIGHT
        runCurrent()
        assertEquals(AppTheme.CLASSIC, model.state.value.theme)
    }

    @Test
    fun failedThemeSaveStaysOnOldSelectionAndSameChoiceRetries() = runTest {
        val preferences = Preferences(AppTheme.CLASSIC)
        val model = AppearanceModel(preferences, backgroundScope)
        preferences.fail = true
        model.choose(AppTheme.LIGHT)
        runCurrent()
        assertEquals(AppTheme.CLASSIC, model.state.value.theme)
        assertEquals(ErrorCode.DATABASE, model.state.value.error?.code)
        preferences.fail = false
        model.choose(AppTheme.LIGHT)
        runCurrent()
        assertEquals(listOf(AppTheme.LIGHT, AppTheme.LIGHT), preferences.writes)
        assertEquals(AppTheme.LIGHT, model.state.value.theme)
        assertNull(model.state.value.error)
        model.close()
    }

    private class Preferences(initial: AppTheme?) : AppearancePreferencesRepository {
        override val selection = MutableStateFlow(initial)
        val writes = mutableListOf<AppTheme>()
        var fail = false

        override suspend fun setTheme(theme: AppTheme): AppResult<Unit> {
            writes += theme
            if (fail)
                return AppResult.Failure(
                    AppError(ErrorCode.DATABASE, "settings.appearance.save", true)
                )
            selection.value = theme
            return AppResult.Success(Unit)
        }
    }
}
