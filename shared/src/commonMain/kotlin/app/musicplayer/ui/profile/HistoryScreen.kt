package app.musicplayer.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.model.Track
import app.musicplayer.ui.components.*

@Composable
fun HistoryScreen(
    model: ProfileModel,
    artwork: ArtworkLoader,
    onBack: () -> Unit,
    onMenu: (Track) -> Unit,
    formatDate: (Long) -> String,
) {
    val state by model.history.collectAsState()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("История", onBack)
        LazyColumn(
            Modifier.weight(1f),
            state = rememberLazyListState(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        ) {
            itemsIndexed(state.data?.items.orEmpty(), key = { _, item -> item.id }) { index, item ->
                Column {
                    TrackRow(
                        item.track,
                        artwork,
                        { model.playHistory(index) },
                        { onMenu(item.track) },
                        showAlbum = false,
                    )
                    Text(
                        formatDate(item.startedAtMs),
                        Modifier.padding(bottom = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.error?.let { item { InlineError(it, model::retry) } }
            if (state.data?.next != null)
                item {
                    TextButton({ model.loadHistory() }, enabled = !state.loading) { Text("Ещё") }
                }
            if (!state.loading && state.data?.items.orEmpty().isEmpty())
                item {
                    Text(
                        "Вы ещё ничего не слушали",
                        Modifier.padding(vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
    }
}
