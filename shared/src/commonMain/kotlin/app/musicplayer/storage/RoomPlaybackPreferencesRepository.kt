package app.musicplayer.storage

import app.musicplayer.diagnostics.DiagnosticEvent
import app.musicplayer.model.ErrorCode
import app.musicplayer.playback.PlaybackPreferencesRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RoomPlaybackPreferencesRepository(
    database: MusicDatabase,
    ownerScope: CoroutineScope,
    private val diagnostics: (DiagnosticEvent) -> Unit = {},
) : PlaybackPreferencesRepository {
    private val dao = database.dao()
    private val enabled = MutableStateFlow(true)
    override val normalizationEnabled = enabled.asStateFlow()
    private val ready = CompletableDeferred<Unit>()
    private val writes = Mutex()

    init {
        ownerScope.launch {
            try {
                val value = dao.setting(KEY)?.value
                if (value != null) {
                    val flag = value.toBooleanStrictOrNull()
                    if (flag != null) enabled.value = flag else record("settings.playback.invalid")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                record("settings.playback.read")
            } finally {
                ready.complete(Unit)
            }
        }
    }

    override suspend fun setNormalizationEnabled(enabled: Boolean) {
        ready.await()
        writes.withLock {
            dao.putSetting(SettingsRecord(KEY, enabled.toString()))
            this.enabled.value = enabled
        }
    }

    private fun record(operation: String) =
        diagnostics(DiagnosticEvent(operation, "storage", null, null, null, ErrorCode.DATABASE, 0))

    private companion object {
        const val KEY = "playback.normalization.enabled"
    }
}
