package app.musicplayer.appearance

import app.musicplayer.model.AppResult
import kotlinx.coroutines.flow.StateFlow

interface AppearancePreferencesRepository {
    val selection: StateFlow<AppTheme?>

    suspend fun setTheme(theme: AppTheme): AppResult<Unit>
}
