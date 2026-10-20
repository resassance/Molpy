package app.musicplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.musicplayer.model.*

@Composable
fun QueueScreen(queue: QueueSnapshot, tracks: Map<TrackId, Track>, onSelect: (QueueEntryId) -> Unit,
    onRemove: (QueueEntryId) -> Unit, onNext: () -> Unit, onPrevious: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Очередь", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPrevious, enabled = queue.currentEntryId != null) { Text("Предыдущая") }
            OutlinedButton(onClick = onNext, enabled = queue.currentEntryId != null) { Text("Следующая") }
        }
        if (queue.entries.isEmpty()) Text("Очередь пуста. Добавьте записи из поиска или медиатеки.")
        queue.entries.forEachIndexed { index, entry ->
            Column(Modifier.fillMaxWidth()) {
                Text("${index + 1}. ${tracks[entry.trackId]?.title ?: "Запись недоступна"}${if (queue.currentEntryId == entry.id) " · текущая" else ""}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSelect(entry.id) }) { Text("Выбрать") }
                    TextButton(onClick = { onRemove(entry.id) }) { Text("Убрать из очереди") }
                }
            }
        }
    }
}
