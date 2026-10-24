package app.musicplayer.ui.settings

import app.musicplayer.appearance.*
import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

data class AppearanceState(
    val theme: AppTheme = AppTheme.DARK,
    val initialized: Boolean = false,
    val saving: Boolean = false,
    val error: AppError? = null,
)

class AppearanceModel(
    private val preferences: AppearancePreferencesRepository,
    ownerScope: CoroutineScope,
) {
    private val job = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + job)
    private val mutable =
        MutableStateFlow(
            AppearanceState(
                theme = preferences.selection.value ?: AppTheme.DARK,
                initialized = preferences.selection.value != null,
            )
        )
    val state = mutable.asStateFlow()
    private val changes = Channel<AppTheme>(Channel.CONFLATED)
    private var failedTarget: AppTheme? = null

    init {
        scope.launch {
            preferences.selection.filterNotNull().collect { theme ->
                mutable.update { it.copy(theme = theme, initialized = true) }
            }
        }
        scope.launch {
            preferences.selection.filterNotNull().first()
            for (theme in changes) {
                mutable.update { it.copy(saving = true, error = null) }
                try {
                    val result = preferences.setTheme(theme)
                    failedTarget = if (result is AppResult.Failure) theme else null
                    mutable.update { it.copy(error = result.errorOrNull()) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failedTarget = theme
                    mutable.update {
                        it.copy(
                            error = AppError(ErrorCode.DATABASE, "settings.appearance.save", true)
                        )
                    }
                } finally {
                    mutable.update {
                        it.copy(saving = false, theme = preferences.selection.value ?: it.theme)
                    }
                }
            }
        }
    }

    fun choose(theme: AppTheme) {
        if (!job.isActive || mutable.value.saving) return
        if (mutable.value.initialized) mutable.update { it.copy(saving = true, error = null) }
        changes.trySend(theme)
    }

    fun retry() {
        failedTarget?.let(::choose)
    }

    fun clearError() {
        failedTarget = null
        mutable.update { it.copy(error = null) }
    }

    fun close() {
        changes.close()
        job.cancel()
    }
}
