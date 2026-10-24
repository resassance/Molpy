package app.musicplayer.storage

import app.musicplayer.appearance.*
import app.musicplayer.diagnostics.DiagnosticEvent
import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RoomAppearancePreferencesRepository(
    database: MusicDatabase,
    ownerScope: CoroutineScope,
    private val diagnostics: (DiagnosticEvent) -> Unit = {},
) : AppearancePreferencesRepository {
    private val dao = database.dao()
    private val mutable = MutableStateFlow<AppTheme?>(null)
    override val selection = mutable.asStateFlow()
    private val ready = CompletableDeferred<Unit>()
    private val writes = Mutex()

    init {
        ownerScope.launch {
            try {
                val stored = dao.setting(KEY)?.value
                val theme = stored?.let { value ->
                    AppTheme.entries.firstOrNull { it.name == value }
                }
                if (stored != null && theme == null) record("settings.appearance.invalid")
                mutable.value = theme ?: AppTheme.DARK
            } catch (_: Exception) {
                // A closed Room database cancels its own job, independently of this owner.
                currentCoroutineContext().ensureActive()
                record("settings.appearance.read")
                mutable.value = AppTheme.DARK
            } finally {
                ready.complete(Unit)
            }
        }
    }

    override suspend fun setTheme(theme: AppTheme): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        ready.await()
        return writes.withLock {
            currentCoroutineContext().ensureActive()
            try {
                dao.putSetting(SettingsRecord(KEY, theme.name))
                mutable.value = theme
                AppResult.Success(Unit)
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                record("settings.appearance.save")
                AppResult.Failure(AppError(ErrorCode.DATABASE, "settings.appearance.save", true))
            }
        }
    }

    private fun record(operation: String) =
        diagnostics(DiagnosticEvent(operation, "storage", null, null, null, ErrorCode.DATABASE, 0))

    private companion object {
        const val KEY = "ui.theme"
    }
}
