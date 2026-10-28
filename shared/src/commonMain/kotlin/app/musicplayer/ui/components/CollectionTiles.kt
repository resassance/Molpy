package app.musicplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun CollectionTile(
    title: String,
    supporting: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
    artwork: @Composable (Modifier) -> Unit,
) {
    Column(modifier.clickable(onClick = onClick).heightIn(min = 48.dp)) {
        artwork(Modifier.fillMaxWidth().aspectRatio(1f))
        Text(
            title,
            Modifier.fillMaxWidth().padding(top = 4.dp),
            textAlign = textAlign,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!supporting.isNullOrBlank())
            Text(
                supporting,
                Modifier.fillMaxWidth(),
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}
