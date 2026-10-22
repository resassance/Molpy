package app.musicplayer.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.listening.ArtistListening
import app.musicplayer.model.Track
import app.musicplayer.ui.components.*

@Composable
fun ProfileScreen(
    model: ProfileModel,
    artwork: ArtworkLoader,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onArtist: (ArtistListening) -> Unit,
    onMenu: (Track) -> Unit,
    formatDate: (Long) -> String,
) {
    val profile by model.profile.collectAsState()
    val history by model.history.collectAsState()
    val data = profile.data
    LazyColumn(
        Modifier.fillMaxSize(),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Профиль", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
                IconButton(onSettings) { Icon(AppIcons.Settings, "Настройки") }
            }
            Text(
                "Время прослушивания",
                Modifier.padding(top = 24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatListeningTime(data?.totalMs ?: 0),
                Modifier.padding(vertical = 4.dp),
                fontSize = 40.sp,
                lineHeight = 46.sp,
                fontWeight = FontWeight.Bold,
            )
            Text("За всё время", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        profile.error?.let { item { InlineError(it, model::retry) } }
        if (profile.loading)
            item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp)) }
        item {
            SectionHeader("Любимые треки")
            Text(
                "По времени прослушивания",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        itemsIndexed(data?.tracks.orEmpty().take(5), key = { _, item -> item.track.id.value }) {
            index,
            item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${index+1}",
                    Modifier.width(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TrackRow(
                    item.track,
                    artwork,
                    { model.playRanking(index) },
                    { onMenu(item.track) },
                    trailingLabel = formatListeningTime(item.playedMs),
                    showAlbum = false,
                )
            }
        }
        if (data?.tracks.orEmpty().isEmpty())
            item {
                Text(
                    "Начните слушать музыку",
                    Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        item {
            SectionHeader("Любимые артисты")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(data?.artists.orEmpty().take(5), key = { it.key }) { artist ->
                    Column(
                        Modifier.width(104.dp).clickable { onArtist(artist) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ArtistAvatar(artist.artist.name, Modifier.size(64.dp))
                        Text(
                            artist.artist.name,
                            Modifier.padding(top = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            formatListeningTime(artist.playedMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            SectionHeader("История прослушивания", "Вся история", onHistory)
        }
        itemsIndexed(history.data?.items.orEmpty().take(5), key = { _, item -> item.id }) {
            index,
            item ->
            Column {
                TrackRow(
                    item.track,
                    artwork,
                    { model.playHistory(index, visibleCount = 5) },
                    { onMenu(item.track) },
                    showAlbum = false,
                )
                Text(
                    formatDate(item.startedAtMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        history.error?.let { item { InlineError(it, model::retry) } }
    }
}
