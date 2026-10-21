package app.musicplayer.ui.settings

import app.musicplayer.model.*
import app.musicplayer.playback.PlaybackPreferencesRepository
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSettingsModelTest {
    private class Preferences(initial: Boolean = true) : PlaybackPreferencesRepository {
        override val normalizationEnabled = MutableStateFlow(initial)
        var failing = false
        var gate: CompletableDeferred<Unit>? = null
        val saved = mutableListOf<Boolean>()

        override suspend fun setNormalizationEnabled(enabled: Boolean) {
            gate?.await()
            if (failing) error("private failure")
            saved += enabled
            normalizationEnabled.value = enabled
        }
    }

    @Test
    fun storedValueAndFailedSavePreserveActualSetting() = runTest {
        val preferences = Preferences(false)
        val model = PlaybackSettingsModel(preferences, backgroundScope)
        runCurrent()
        assertFalse(model.state.value.enabled)
        preferences.failing = true
        model.setEnabled(true)
        runCurrent()
        assertFalse(model.state.value.enabled)
        assertEquals(ErrorCode.DATABASE, model.state.value.error?.code)
        preferences.failing = false
        model.setEnabled(true)
        runCurrent()
        assertTrue(model.state.value.enabled)
        assertFalse(model.state.value.saving)
        model.close()
    }

    @Test
    fun rapidChangesCannotReorderDurableSetting() = runTest {
        val preferences = Preferences()
        val gate = CompletableDeferred<Unit>()
        preferences.gate = gate
        val model = PlaybackSettingsModel(preferences, backgroundScope)
        model.setEnabled(false)
        runCurrent()
        model.setEnabled(true)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(false, true), preferences.saved)
        assertTrue(model.state.value.enabled)
        model.close()
    }
}
