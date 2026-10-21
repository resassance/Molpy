package app.musicplayer.ui.settings

import app.musicplayer.model.*
import app.musicplayer.playback.PlaybackPreferencesRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

data class PlaybackSettingsState(
    val enabled: Boolean = true,
    val saving: Boolean = false,
    val error: AppError? = null,
)

class PlaybackSettingsModel(
    private val preferences: PlaybackPreferencesRepository,
    ownerScope: CoroutineScope,
) {
    private val job = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + job)
    private val mutable =
        MutableStateFlow(PlaybackSettingsState(enabled = preferences.normalizationEnabled.value))
    val state = mutable.asStateFlow()
    private val changes = Channel<Boolean>(Channel.CONFLATED)
    private var requested = preferences.normalizationEnabled.value

    init {
        scope.launch {
            preferences.normalizationEnabled.collect { enabled ->
                mutable.update { it.copy(enabled = enabled) }
            }
        }
        scope.launch {
            for (enabled in changes) {
                mutable.update { it.copy(saving = true, error = null) }
                try {
                    preferences.setNormalizationEnabled(enabled)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutable.update {
                        it.copy(
                            error = AppError(ErrorCode.DATABASE, "settings.playback.save", true)
                        )
                    }
                } finally {
                    mutable.update {
                        it.copy(saving = false, enabled = preferences.normalizationEnabled.value)
                    }
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        requested = enabled
        changes.trySend(enabled)
    }

    fun retry() = setEnabled(requested)

    fun close() {
        changes.close()
        job.cancel()
    }
}
