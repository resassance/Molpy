package app.musicplayer.ui.library

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.ImeAction
import app.musicplayer.ui.components.*

@Composable
fun PlaylistEditDialog(
    initial: String,
    renaming: Boolean,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    val valid = name.trim().length in 1..120
    MolpyDialog(
        onDismissRequest = onDismiss,
        title = if (renaming) "Название плейлиста" else "Новый плейлист",
        icon = if (renaming) AppIcons.Edit else AppIcons.PlaylistAdd,
        text = {
            OutlinedTextField(
                name,
                { name = it },
                enabled = !busy,
                singleLine = true,
                label = { Text("Название") },
                supportingText = { if (name.length > 120) Text("Не больше 120 символов") },
                isError = name.length > 120,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions =
                    KeyboardActions(onDone = { if (valid && !busy) onSubmit(name.trim()) }),
            )
        },
        confirmButton = {
            TextButton({ onSubmit(name.trim()) }, enabled = valid && !busy) {
                Text(if (busy) "Сохраняем…" else "Сохранить")
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    )
}
