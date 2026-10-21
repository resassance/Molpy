package app.musicplayer.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.musicplayer.ui.components.InlineError

@Composable
fun PlaybackSettingsSection(model: PlaybackSettingsModel) {
    val state by model.state.collectAsState()
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Выравнивать громкость", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Уменьшать громкость записей по данным источника",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                state.enabled,
                model::setEnabled,
                enabled = !state.saving,
                modifier = Modifier.semantics { contentDescription = "Выравнивать громкость" },
            )
        }
        state.error?.let { InlineError(it, model::retry) }
    }
}
