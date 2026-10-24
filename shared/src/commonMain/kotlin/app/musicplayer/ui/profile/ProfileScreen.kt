package app.musicplayer.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        item(key = "header") {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Профиль",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onSettings) { Icon(AppIcons.Settings, "Настройки") }
            }
            Surface(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        formatListeningTime(data?.totalMs ?: 0),
                        fontSize = 40.sp,
                        lineHeight = 46.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Время прослушивания",
                        Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        profile.error?.let { item(key = "profile-error") { InlineError(it, model::retry) } }
        if (profile.loading)
            item(key = "profile-loading") {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
            }
        item(key = "tracks-header") { SectionHeader("Любимые треки") }
        itemsIndexed(
            data?.tracks.orEmpty().take(5),
            key = { _, item -> "ranking-${item.track.id.value}" },
        ) { index, item ->
            TrackRow(
                item.track,
                artwork,
                { model.playRanking(index) },
                { onMenu(item.track) },
                trailingLabel = formatListeningTime(item.playedMs),
                showAlbum = false,
            )
        }
        if (data?.tracks.orEmpty().isEmpty())
            item(key = "tracks-empty") {
                Text(
                    "Начните слушать музыку",
                    Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        item(key = "artists-header") { SectionHeader("Любимые артисты") }
        items(data?.artists.orEmpty().take(5), key = { "artist-${it.key}" }) { artist ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable { onArtist(artist) }
                    .heightIn(min = 72.dp)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtistPhoto(artist.artist.name, artist.artist.source, artwork, Modifier.size(56.dp))
                Text(
                    artist.artist.name,
                    Modifier.weight(1f).padding(horizontal = 12.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    formatListeningTime(artist.playedMs),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item(key = "history-header") {
            SectionHeader("История прослушивания", "Вся история", onHistory)
        }
        itemsIndexed(
            history.data?.items.orEmpty().take(5),
            key = { _, item -> "history-${item.id}" },
        ) { index, item ->
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
        history.error?.let { item(key = "history-error") { InlineError(it, model::retry) } }
        item(key = "bottom-space") { Spacer(Modifier.height(8.dp)) }
    }
}
