package app.musicplayer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun CompactHeader(
    title: String = "",
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = if (onBack == null) 16.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        onBack?.let { IconButton(it) { Icon(AppIcons.Back, "Назад") } }
        Text(
            title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        action?.invoke()
    }
}
