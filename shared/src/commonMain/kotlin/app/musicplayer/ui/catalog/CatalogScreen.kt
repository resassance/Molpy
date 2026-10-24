package app.musicplayer.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.catalog.*
import app.musicplayer.model.*
import app.musicplayer.ui.components.*
import app.musicplayer.ui.profile.formatListeningTime
import app.musicplayer.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    model: CatalogModel,
    artwork: ArtworkLoader,
    onBack: () -> Unit,
    onMenu: (Track) -> Unit,
    onAlbum: (AlbumRef) -> Unit,
    onMerge: (() -> Unit)? = null,
    onMerges: (() -> Unit)? = null,
    playedMs: Long? = null,
    onShuffle: ((List<Track>) -> Unit)? = null,
) {
    val state by model.state.collectAsState()
    val colors = rememberArtworkColors(if (state.albumRef != null) state.artwork else null)
    Box(Modifier.fillMaxSize()) {
        if (state.albumRef != null) ArtworkAmbientBackground(colors, Modifier.matchParentSize())
        Column(Modifier.fillMaxSize()) {
            var menu by remember { mutableStateOf(false) }
            ScreenHeader(
                if (state.albumRef != null) "Альбом" else "Артист",
                onBack,
                action =
                    if (onMerge == null) null
                    else {
                        {
                            Box {
                                IconButton({ menu = true }) {
                                    Icon(AppIcons.More, "Действия с артистом")
                                }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Объединить с…") },
                                        leadingIcon = { Icon(AppIcons.Plus, null) },
                                        onClick = {
                                            menu = false
                                            onMerge()
                                        },
                                    )
                                    onMerges?.let { action ->
                                        DropdownMenuItem(
                                            text = { Text("Объединённые карточки") },
                                            leadingIcon = { Icon(AppIcons.Edit, null) },
                                            onClick = {
                                                menu = false
                                                action()
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    },
            )
            PrimaryTabRow(
                selectedTabIndex = state.section.ordinal,
                containerColor = Color.Transparent,
            ) {
                CatalogSection.entries.forEach { section ->
                    Tab(
                        state.section == section,
                        { model.selectSection(section) },
                        text = { Text(if (section == CatalogSection.ALL) "Все" else "Избранное") },
                    )
                }
            }
            LazyColumn(
                Modifier.weight(1f),
                state = rememberLazyListState(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            ) {
                item {
                    if (state.albumRef != null) {
                        val tracks =
                            if (state.section == CatalogSection.FAVORITES) state.favoriteTracks
                            else state.allTracks()
                        val complete =
                            !state.tracks.loading &&
                                state.tracks.error == null &&
                                state.tracks.data?.next == null &&
                                (state.localTracks.isNotEmpty() || state.tracks.data != null)
                        CollectionHeader(
                            title = state.title,
                            countLabel = trackCount(tracks.size),
                            artwork = { modifier ->
                                TrackArtwork(
                                    state.artwork ?: Artwork(ArtworkKind.PLACEHOLDER, null, null),
                                    artwork,
                                    modifier,
                                )
                            },
                            onPlay = if (tracks.isEmpty()) null else ({ model.play(0) }),
                            onShuffle =
                                if (tracks.isEmpty() || !complete || onShuffle == null) null
                                else ({ onShuffle(tracks) }),
                            supportingContent = {
                                if (state.artists.isNotEmpty())
                                    Text(
                                        state.artists.map { artistName(it) }.joinToString(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                            },
                        )
                    } else
                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val art = state.artwork
                            if (art != null)
                                TrackArtwork(
                                    art,
                                    artwork,
                                    Modifier.size(160.dp)
                                        .then(
                                            if (state.artistRef != null) Modifier.clip(CircleShape)
                                            else Modifier
                                        ),
                                )
                            else if (state.artistRef != null)
                                ArtistAvatar(state.title, Modifier.size(96.dp))
                            Text(
                                state.title,
                                Modifier.padding(top = 16.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.headlineMedium,
                            )
                            if (state.artists.isNotEmpty())
                                Text(
                                    state.artists.map { artistName(it) }.joinToString(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            if (state.artistId != null)
                                Text(
                                    playedMs?.let { "Прослушано: ${formatListeningTime(it)}" }
                                        ?: "Прослушано: —",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            Spacer(Modifier.height(16.dp))
                        }
                }
                if (state.section == CatalogSection.FAVORITES) {
                    state.collectionError?.let { item { InlineError(it) } }
                    itemsIndexed(state.favoriteTracks, key = { _, track -> track.id.value }) {
                        index,
                        track ->
                        TrackRow(
                            track,
                            artwork,
                            { model.play(index) },
                            { onMenu(track) },
                            showAlbum = false,
                        )
                    }
                    if (state.favoriteTracks.isEmpty())
                        item {
                            Text(
                                "Здесь пока нет избранных треков",
                                Modifier.padding(vertical = 24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                } else {
                    if (state.tracks.loading)
                        item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    state.tracks.error?.let { item { InlineError(it, model::retry) } }
                    itemsIndexed(
                        state.allTracks(),
                        key = { index, track -> "$index:${track.id.value}" },
                    ) { index, track ->
                        TrackRow(
                            track,
                            artwork,
                            { model.play(index) },
                            { onMenu(track) },
                            showAlbum = false,
                        )
                    }
                    if (state.tracks.data?.next != null)
                        item {
                            TextButton(
                                { model.loadMore(CatalogPart.TRACKS) },
                                enabled = !state.tracks.loading,
                            ) {
                                Text("Ещё треки")
                            }
                        }
                    if (
                        !state.tracks.loading &&
                            state.tracks.error == null &&
                            state.allTracks().isEmpty()
                    )
                        item {
                            Text(
                                "Доступных треков пока нет",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    releaseSection(
                        "Релизы",
                        state.albums,
                        artwork,
                        { model.loadMore(CatalogPart.ALBUMS) },
                        onAlbum,
                    )
                    releaseSection(
                        "Синглы и EP",
                        state.singles,
                        artwork,
                        { model.loadMore(CatalogPart.SINGLES) },
                        onAlbum,
                    )
                }
                state.commandError?.let { item { InlineError(it) } }
            }
        }
    }
}

private fun LazyListScope.releaseSection(
    title: String,
    state: LoadState<CatalogPage<AlbumCard>>,
    artwork: ArtworkLoader,
    onMore: () -> Unit,
    onAlbum: (AlbumRef) -> Unit,
) {
    if (state.data?.items?.isNotEmpty() == true) {
        item { SectionHeader(title) }
        itemsIndexed(
            state.data.items,
            key = { index, card -> "$index:${card.album.source?.id.orEmpty()}" },
        ) { _, album ->
            Row(
                Modifier.fillMaxWidth().clickable { onAlbum(album.album) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackArtwork(album.artwork, artwork, Modifier.size(64.dp))
                Text(
                    album.album.name,
                    Modifier.padding(start = 12.dp).weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
    state.error?.let { item { InlineError(it, onMore) } }
    if (state.data?.next != null)
        item {
            TextButton(onMore, enabled = !state.loading) {
                Text(if (state.loading) "Загружаем…" else "Ещё релизы")
            }
        }
}
