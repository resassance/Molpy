package app.musicplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import app.musicplayer.artists.*
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.collections.*
import app.musicplayer.downloads.DownloadState
import app.musicplayer.model.*
import app.musicplayer.ui.catalog.CatalogScreen
import app.musicplayer.ui.catalog.PublicPlaylistScreen
import app.musicplayer.ui.downloads.DownloadsScreen
import app.musicplayer.ui.library.*
import app.musicplayer.ui.navigation.*
import app.musicplayer.ui.profile.*
import app.musicplayer.ui.search.SearchScreen
import app.musicplayer.ui.settings.AppearanceScreen
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
    onShareDiagnostics: (() -> Unit)? = null,
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
    val directory = models.artists?.state?.collectAsState()?.value?.data
    fun artistRoute(ref: ArtistRef, key: String): AppRoute.Artist {
        val id = ArtistId(key).takeIf { it in directory?.profiles.orEmpty() }
        return AppRoute.Artist(ref, key, id)
    }
    val artist: (SavedArtist) -> Unit = { navigation.push(artistRoute(it.ref, it.key)) }
    LaunchedEffect(route, directory?.revision) {
        when (route) {
            is AppRoute.PublicPlaylist -> models.publicPlaylist?.open(route.ref)
            is AppRoute.Album -> models.catalog.album(route.ref, route.localKey)
            is AppRoute.Artist ->
                if (route.artistId != null) models.catalog.localArtist(route.artistId)
                else models.catalog.artist(route.ref, route.localKey)
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
                        onPlayFavorites = {
                            models.library.play(collection.data?.favorites.orEmpty(), 0)
                        },
                        onSearch = { navigation.select(AppTab.SEARCH) },
                        onProfile = { navigation.select(AppTab.PROFILE) },
                        onSettings = { navigation.push(AppRoute.Settings) },
                    )
                AppTab.SEARCH ->
                    SearchScreen(
                        models.search,
                        artwork,
                        models.player.state.value.currentTrack?.id,
                        onMenu,
                        onArtist = { navigation.push(AppRoute.Artist(it)) },
                        onAlbum = { navigation.push(AppRoute.Album(it)) },
                        onPublicPlaylist = { navigation.push(AppRoute.PublicPlaylist(it)) },
                    )
                AppTab.PROFILE ->
                    ProfileScreen(
                        models.profile,
                        artwork,
                        { navigation.push(AppRoute.Settings) },
                        { navigation.push(AppRoute.History) },
                        { navigation.push(artistRoute(it.artist, it.key)) },
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
                onShuffle = models.library::playShuffled,
            )
        is AppRoute.PublicPlaylist -> {
            val model = models.publicPlaylist
            if (model != null) {
                val state by model.state.collectAsState()
                if (state.ref?.source == route.ref.source)
                    PublicPlaylistScreen(model, artwork, back, onMenu)
                else
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
            }
        }
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
                        if (route.artistId != null)
                            state.artistId == route.artistId ||
                                directory?.let { d ->
                                    route.artistId
                                        .takeIf { it in d.profiles }
                                        ?.let { ArtistResolver.root(it, d) == state.artistId }
                                } == true
                        else
                            state.artistRef?.source == route.ref.source &&
                                state.artistRef != null &&
                                state.localKey == route.localKey
                }
            val profile by models.profile.profile.collectAsState()
            if (ready)
                CatalogScreen(
                    models.catalog,
                    artwork,
                    back,
                    onMenu,
                    { navigation.push(AppRoute.Album(it)) },
                    onMerge =
                        state.artistId?.let { id ->
                            { navigation.open(AppOverlay.ArtistMergePicker(id)) }
                        },
                    onMerges =
                        state.artistId?.let { id ->
                            { navigation.open(AppOverlay.ArtistMerges(id)) }
                        },
                    playedMs =
                        profile.data
                            ?.takeIf { it.artistRevision == directory?.revision }
                            ?.artists
                            ?.firstOrNull { it.key == state.artistId?.value }
                            ?.playedMs ?: 0L,
                    onShuffle = models.library::playShuffled,
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
        AppRoute.Appearance -> models.appearance?.let { AppearanceScreen(it, back) }
        AppRoute.Settings ->
            NetworkSettingsScreen(
                models.settings,
                back,
                playback = models.playbackSettings,
                onShareDiagnostics = onShareDiagnostics,
                onAppearance = models.appearance?.let { { navigation.push(AppRoute.Appearance) } },
            ) { password ->
                scope.launch { models.settings.apply(password) }
            }
    }
}
