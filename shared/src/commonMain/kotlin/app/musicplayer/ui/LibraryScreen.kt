package app.musicplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import app.musicplayer.model.*

@Composable
fun LibraryScreen(favorites: List<Track>, recent: List<Track>, onSelect: (Track) -> Unit,
    onAdd: (Track) -> Unit, onFavorite: (Track, Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Избранное", style = MaterialTheme.typography.titleMedium)
        if (favorites.isEmpty()) Text("В избранном пока нет записей.")
        favorites.forEach { track -> LibraryRow(track, true, onSelect, onAdd, onFavorite) }
        Text("Недавно прослушано", style = MaterialTheme.typography.titleMedium)
        if (recent.isEmpty()) Text("История прослушивания пока пуста.")
        recent.forEach { track -> LibraryRow(track, favorites.any { it.id == track.id }, onSelect, onAdd, onFavorite) }
    }
}

@Composable
private fun LibraryRow(track: Track, favorite: Boolean, onSelect: (Track) -> Unit,
    onAdd: (Track) -> Unit, onFavorite: (Track, Boolean) -> Unit) {
    Text(track.title)
    Text(track.artists.joinToString(", ") { it.name }.ifBlank { "Исполнитель неизвестен" })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onSelect(track) }) { Text("Слушать") }
        OutlinedButton(onClick = { onAdd(track) }) { Text("В очередь") }
    }
    TextButton(onClick = { onFavorite(track, !favorite) }) { Text(if (favorite) "Убрать из избранного" else "В избранное") }
}
