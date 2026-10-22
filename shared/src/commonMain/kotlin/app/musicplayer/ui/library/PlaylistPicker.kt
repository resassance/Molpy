package app.musicplayer.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.collections.*
import app.musicplayer.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPicker(
    playlists: List<PlaylistSummary>,
    artwork: ArtworkLoader,
    onChoose: (PlaylistId) -> Unit,
    onCreate: () -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 640.dp)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            Text("Добавить в плейлист", style = MaterialTheme.typography.titleLarge)
            MenuAction("Новый плейлист", AppIcons.Plus, onCreate)
            LazyColumn(
                Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(playlists, key = { it.id.value }) { playlist ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onChoose(playlist.id) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlaylistCollage(playlist.artworks, artwork, Modifier.size(56.dp))
                        Text(playlist.name, Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }
}
