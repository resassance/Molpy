package app.musicplayer.ui.artists

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.musicplayer.artists.*
import app.musicplayer.ui.components.*

@Composable
fun ArtistEditorSheet(
    state: ArtistEditorState,
    directory: ArtistDirectory,
    onReplace: (Int) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    ArtistSheetContent("Исполнители трека", onClose) {
        state.track?.let { Text(it.title, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(
            "Изменения коснутся только этого трека.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.choices.forEachIndexed { index, choice ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ArtistOption(
                    choiceName(choice, directory),
                    when (choice) {
                        is ArtistChoice.Name -> "Без ссылки на профиль"
                        is ArtistChoice.Catalog -> "YouTube Music"
                        is ArtistChoice.Existing -> "Из медиатеки"
                    },
                    { onReplace(index) },
                    Modifier.weight(1f),
                    !state.saving,
                )
                IconButton(
                    { onRemove(index) },
                    enabled = !state.saving,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(AppIcons.Close, "Убрать исполнителя ${index+1}")
                }
            }
        }
        MenuAction("Добавить исполнителя", AppIcons.Plus, onAdd, !state.saving)
        ArtistError(state.error)
        if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.expected != null)
            MenuAction("Вернуть исходные данные", AppIcons.Retry, onReset, !state.saving)
        MenuAction("Сохранить", AppIcons.Check, onSave, !state.saving && state.choices.isNotEmpty())
    }
}
