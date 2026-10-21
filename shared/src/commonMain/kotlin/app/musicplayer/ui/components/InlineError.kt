package app.musicplayer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.musicplayer.model.AppError
import app.musicplayer.ui.present

@Composable
fun InlineError(error: AppError, onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            present(error).message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (error.retryable && onRetry != null) TextButton(onClick = onRetry) { Text("Повторить") }
    }
}
