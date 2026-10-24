package app.musicplayer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun CollectionHeader(
    title: String,
    countLabel: String,
    artwork: @Composable (Modifier) -> Unit,
    onPlay: (() -> Unit)?,
    modifier: Modifier = Modifier,
    supportingContent: @Composable ColumnScope.() -> Unit = {},
    onShuffle: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            artwork(Modifier.size(88.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    countLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (onPlay != null || onShuffle != null) {
                    Row(
                        Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        onPlay?.let {
                            FilledIconButton(
                                it,
                                Modifier.size(48.dp),
                                shape = CircleShape,
                                colors =
                                    IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.onSurface,
                                        contentColor = MaterialTheme.colorScheme.surface,
                                    ),
                            ) {
                                Icon(AppIcons.Play, "Воспроизвести", Modifier.size(28.dp))
                            }
                        }
                        onShuffle?.let {
                            FilledIconButton(
                                it,
                                Modifier.size(48.dp),
                                shape = CircleShape,
                                colors =
                                    IconButtonDefaults.filledIconButtonColors(
                                        containerColor =
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                    ),
                            ) {
                                Icon(AppIcons.Shuffle, "Перемешать", Modifier.size(26.dp))
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth(), content = supportingContent)
    }
}
