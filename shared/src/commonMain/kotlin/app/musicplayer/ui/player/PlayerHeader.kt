package app.musicplayer.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musicplayer.model.AlbumRef
import app.musicplayer.ui.components.AppIcons

@Composable
internal fun PlayerHeader(
    album: AlbumRef?,
    onClose: () -> Unit,
    onMenu: () -> Unit,
    onAlbum: (AlbumRef) -> Unit,
    menuEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClose, Modifier.size(48.dp)) { Icon(AppIcons.Down, "Свернуть плеер") }
        val namedAlbum = album?.takeIf { it.name.isNotBlank() }
        val albumModifier =
            Modifier.weight(1f).heightIn(min = 48.dp).let {
                if (namedAlbum?.source != null)
                    it.clickable(role = Role.Button) { onAlbum(namedAlbum) }
                else it
            }
        Column(
            albumModifier,
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (namedAlbum == null)
                Text(
                    "Сейчас играет",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else {
                Text(
                    "Альбом",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    namedAlbum.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
        IconButton(onMenu, Modifier.size(48.dp), enabled = menuEnabled) {
            Icon(AppIcons.More, "Действия с треком")
        }
    }
}
