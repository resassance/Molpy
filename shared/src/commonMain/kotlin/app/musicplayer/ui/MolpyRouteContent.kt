package app.musicplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.collections.*
import app.musicplayer.downloads.DownloadState
import app.musicplayer.model.*
import app.musicplayer.ui.catalog.CatalogScreen
import app.musicplayer.ui.downloads.DownloadsScreen
import app.musicplayer.ui.library.*
import app.musicplayer.ui.navigation.*
import app.musicplayer.ui.profile.*
import app.musicplayer.ui.search.SearchScreen
import app.musicplayer.ui.settings.NetworkSettingsScreen
import kotlinx.coroutines.launch

@Composable
internal fun MolpyRouteContent(
    route: AppRoute,
    models: MolpyModels,
    artwork: ArtworkLoader,
    navigation: AppNavigation,
    onMenu: (Track) -> Unit,
    onCreate: () -> Unit,
    onRename: (PlaylistDetail) -> Unit,
    onDelete: (PlaylistId) -> Unit,
    onManageStorage: () -> Unit,
    formatDate: (Long) -> String,
) {
    val collection by models.library.state.collectAsState()
    val downloads by models.downloads.state.collectAsState()
    val scope = rememberCoroutineScope()
    val back = {
        navigation.back()
        Unit
    }
    val playlist: (PlaylistSummary) -> Unit = { navigation.push(AppRoute.Playlist(it.id)) }
    val album: (SavedAlbum) -> Unit = { navigation.push(AppRoute.Album(it.ref, it.key)) }
    val artist: (SavedArtist) -> Unit = { navigation.push(AppRoute.Artist(it.ref, it.key)) }
    LaunchedEffect(route) {
        when (route) {
            is AppRoute.Album -> models.catalog.album(route.ref, route.localKey)
            is AppRoute.Artist -> models.catalog.artist(route.ref, route.localKey)
            AppRoute.Root(AppTab.PROFILE),
            AppRoute.History -> models.profile.loadHistory(reset = true)
            else -> Unit
        }
    }
    when (route) {
        is AppRoute.Root ->
            when (route.tab) {
                AppTab.LIBRARY ->
                    app.musicplayer.ui.library.LibraryScreen(
                        collection,
                        artwork,
                        downloads.assets.count { it.state == DownloadState.AVAILABLE },
                        downloads.usage.totalBytes,
                        { navigation.push(AppRoute.Favorites) },
                        { navigation.push(AppRoute.Playlists) },
                        onCreate,
                        playlist,
                        { navigation.push(AppRoute.Albums) },
                        album,
                        { navigation.push(AppRoute.Artists) },
                        artist,
                        { navigation.push(AppRoute.Downloads) },
                        models.library::retry,
                    )
                AppTab.SEARCH ->
                    SearchScreen(
                        models.search,
                        artwork,
                        models.player.state.value.currentTrack?.id,
                        onMenu,
                    )
                AppTab.PROFILE ->
                    ProfileScreen(
                        models.profile,
                        artwork,
                        { navigation.push(AppRoute.Settings) },
                        { navigation.push(AppRoute.History) },
                        { navigation.push(AppRoute.Artist(it.artist, it.key)) },
                        onMenu,
                        formatDate,
                    )
            }
        AppRoute.Favorites,
        AppRoute.Playlists,
        AppRoute.Albums,
        AppRoute.Artists ->
            CollectionListScreen(
                when (route) {
                    AppRoute.Favorites -> LibrarySection.FAVORITES
                    AppRoute.Playlists -> LibrarySection.PLAYLISTS
                    AppRoute.Albums -> LibrarySection.ALBUMS
                    else -> LibrarySection.ARTISTS
                },
                collection,
                artwork,
                back,
                models.library::play,
                onMenu,
                playlist,
                album,
                artist,
                onCreate,
            )
        is AppRoute.Playlist ->
            PlaylistScreen(route.id, models.library, artwork, back, onRename, onDelete, onMenu)
        is AppRoute.Album,
        is AppRoute.Artist -> {
            val state by models.catalog.state.collectAsState()
            val ready =
                when (route) {
                    is AppRoute.Album ->
                        state.albumRef?.source == route.ref.source &&
                            state.albumRef != null &&
                            state.localKey == route.localKey
                    is AppRoute.Artist ->
                        state.artistRef?.source == route.ref.source &&
                            state.artistRef != null &&
                            state.localKey == route.localKey
                }
            if (ready)
                CatalogScreen(
                    models.catalog,
                    artwork,
                    back,
                    onMenu,
                    { navigation.push(AppRoute.Album(it)) },
                )
            else
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
        }
        AppRoute.Downloads ->
            app.musicplayer.ui.downloads.DownloadsScreen(
                models.downloads,
                artwork,
                back,
                models.library::play,
                onManageStorage,
            )
        AppRoute.History -> HistoryScreen(models.profile, artwork, back, onMenu, formatDate)
        AppRoute.Settings ->
            NetworkSettingsScreen(models.settings, back) { password ->
                scope.launch { models.settings.apply(password) }
            }
    }
}
