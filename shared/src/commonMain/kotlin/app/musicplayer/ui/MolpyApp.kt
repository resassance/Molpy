package app.musicplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.collections.*
import app.musicplayer.model.*
import app.musicplayer.ui.components.*
import app.musicplayer.ui.library.*
import app.musicplayer.ui.navigation.*
import app.musicplayer.ui.player.*
import app.musicplayer.ui.theme.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*

private data class PlaylistEdit(
    val id: PlaylistId?,
    val name: String,
    val track: Track? = null,
    val requestId: String = Uuid.random().toString(),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MolpyApp(
    models: MolpyModels,
    artwork: ArtworkLoader,
    navigation: AppNavigation,
    onManageStorage: () -> Unit,
    formatDate: (Long) -> String,
    backHandler: @Composable (Boolean, () -> Unit) -> Unit,
) {
    val player by models.player.state.collectAsState()
    val library by models.library.state.collectAsState()
    val downloads by models.downloads.state.collectAsState()
    val nav by navigation.state.collectAsState()
    val colors by models.palette.state.collectAsState()
    val epoch by models.artworkEpoch.collectAsState()
    val scope = rememberCoroutineScope()
    val saved = rememberSaveableStateHolder()
    val keyboard = LocalSoftwareKeyboardController.current
    val ime = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val snackbar = remember { SnackbarHostState() }
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    var edit by remember { mutableStateOf<PlaylistEdit?>(null) }
    var busyEdit by remember { mutableStateOf<String?>(null) }
    val favoriteIds = library.data?.favorites.orEmpty().map { it.id }.toSet()
    val track = player.currentTrack
    LaunchedEffect(track?.id, track?.artwork, epoch) {
        models.palette.update(track?.id, track?.artwork)
    }
    val libraryError by models.library.commandError.collectAsState()
    val profileError by models.profile.commandError.collectAsState()
    val searchError by models.search.commandError.collectAsState()
    LaunchedEffect(libraryError) {
        libraryError?.let { snackbar.showSnackbar(present(it).message) }
    }
    LaunchedEffect(profileError) {
        profileError?.let { snackbar.showSnackbar(present(it).message) }
    }
    LaunchedEffect(searchError) { searchError?.let { snackbar.showSnackbar(present(it).message) } }
    backHandler(ime || nav.overlays.isNotEmpty() || nav.stacks.getValue(nav.selectedTab).size > 1) {
        if (ime) keyboard?.hide() else navigation.back()
    }
    fun menu(value: Track) {
        menuTrack = value
        navigation.open(AppOverlay.TrackMenu(value.id))
    }
    fun finish(expected: AppOverlay) {
        if (navigation.state.value.overlays.lastOrNull() == expected) navigation.back()
    }
    suspend fun action(block: suspend () -> AppResult<Unit>): Boolean {
        val result =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                AppResult.Failure(AppError(ErrorCode.UNKNOWN, "app.action", true))
            }
        return when (result) {
            is AppResult.Success -> true
            is AppResult.Failure -> {
                snackbar.showSnackbar(present(result.error).message)
                false
            }
        }
    }
    MolpyTheme(colors) {
        CompositionLocalProvider(LocalArtworkEpoch provides epoch) {
            Box(
                Modifier.fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(colors.surface), Color(colors.background))
                        )
                    )
            ) {
                Scaffold(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    snackbarHost = { SnackbarHost(snackbar) },
                    modifier = Modifier.safeDrawingPadding().imePadding(),
                    bottomBar = {
                        if (!ime)
                            Column {
                                MiniPlayer(
                                    player,
                                    artwork,
                                    {
                                        keyboard?.hide()
                                        navigation.open(AppOverlay.NowPlaying)
                                    },
                                    models.player::playPause,
                                    models.player::next,
                                )
                                NavigationBar(containerColor = Color.Transparent) {
                                    AppTab.entries.forEach { tab ->
                                        NavigationBarItem(
                                            selected = nav.selectedTab == tab,
                                            onClick = {
                                                keyboard?.hide()
                                                navigation.select(tab)
                                            },
                                            colors =
                                                NavigationBarItemDefaults.colors(
                                                    indicatorColor = Color.Transparent,
                                                    selectedIconColor =
                                                        MaterialTheme.colorScheme.primary,
                                                    selectedTextColor =
                                                        MaterialTheme.colorScheme.primary,
                                                    unselectedIconColor =
                                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                                    unselectedTextColor =
                                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                                ),
                                            icon = {
                                                Icon(
                                                    when (tab) {
                                                        AppTab.LIBRARY -> AppIcons.Library
                                                        AppTab.SEARCH -> AppIcons.Search
                                                        AppTab.PROFILE -> AppIcons.Profile
                                                    },
                                                    null,
                                                )
                                            },
                                            label = {
                                                Text(
                                                    tab.label,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow =
                                                        androidx.compose.ui.text.style.TextOverflow
                                                            .Ellipsis,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        saved.SaveableStateProvider("${nav.selectedTab}:${nav.current}") {
                            MolpyRouteContent(
                                nav.current,
                                models,
                                artwork,
                                navigation,
                                ::menu,
                                { edit = PlaylistEdit(null, "") },
                                { edit = PlaylistEdit(it.id, it.name) },
                                { navigation.open(AppOverlay.ConfirmDeletePlaylist(it)) },
                                onManageStorage,
                                formatDate,
                            )
                        }
                    }
                }
                if (AppOverlay.NowPlaying in nav.overlays)
                    Surface(Modifier.fillMaxSize()) {
                        NowPlayingScreen(
                            player,
                            models.player,
                            artwork,
                            track?.id in favoriteIds,
                            {
                                track?.let { value ->
                                    scope.launch {
                                        action {
                                            models.actions.favorite(value, value.id !in favoriteIds)
                                        }
                                    }
                                }
                            },
                            { navigation.back() },
                            { navigation.open(AppOverlay.Queue) },
                            { navigation.open(AppOverlay.Lyrics) },
                            ::menu,
                            { navigation.push(AppRoute.Artist(it)) },
                            { navigation.push(AppRoute.Album(it)) },
                        )
                    }
                when (val overlay = nav.overlays.lastOrNull()) {
                    AppOverlay.Queue ->
                        QueueSheet(player, models.player, artwork) { navigation.back() }
                    AppOverlay.Lyrics ->
                        LyricsScreen(player, { navigation.back() }, models.player::loadLyrics)
                    is AppOverlay.TrackMenu ->
                        menuTrack
                            ?.takeIf { it.id == overlay.trackId }
                            ?.let { value ->
                                val selection = selectDownload(value, player.snapshot)
                                TrackMenu(
                                    value,
                                    value.id in favoriteIds,
                                    selection != null,
                                    {
                                        scope.launch {
                                            if (
                                                action {
                                                    models.actions.favorite(
                                                        value,
                                                        value.id !in favoriteIds,
                                                    )
                                                }
                                            )
                                                finish(overlay)
                                        }
                                    },
                                    {
                                        scope.launch {
                                            if (action { models.actions.enqueue(value) }) {
                                                val stillHere =
                                                    navigation.state.value.overlays.lastOrNull() ==
                                                        overlay
                                                finish(overlay)
                                                if (
                                                    stillHere &&
                                                        models.player.state.value.currentTrack ==
                                                            null
                                                )
                                                    navigation.open(AppOverlay.Queue)
                                            }
                                        }
                                    },
                                    {
                                        finish(overlay)
                                        navigation.open(AppOverlay.PlaylistPicker(value.id))
                                    },
                                    {
                                        selection?.let { chosen ->
                                            scope.launch {
                                                if (
                                                    action {
                                                        models.actions.download(
                                                            chosen.track,
                                                            chosen.candidate,
                                                        )
                                                    }
                                                )
                                                    finish(overlay)
                                            }
                                        }
                                    },
                                    {
                                        navigation.select(AppTab.LIBRARY)
                                        navigation.push(AppRoute.Downloads)
                                    },
                                    downloads.assets.any { it.trackId == value.id },
                                    { finish(overlay) },
                                )
                            }
                    is AppOverlay.PlaylistPicker ->
                        menuTrack
                            ?.takeIf { it.id == overlay.trackId }
                            ?.let { value ->
                                PlaylistPicker(
                                    library.data?.playlists.orEmpty(),
                                    artwork,
                                    { id ->
                                        scope.launch {
                                            if (action { models.actions.append(id, value) })
                                                finish(overlay)
                                        }
                                    },
                                    { edit = PlaylistEdit(null, "", value) },
                                    { finish(overlay) },
                                )
                            }
                    is AppOverlay.ConfirmDeletePlaylist ->
                        AlertDialog(
                            onDismissRequest = { finish(overlay) },
                            title = { Text("Удалить плейлист?") },
                            text = { Text("Треки останутся в избранном и на устройстве.") },
                            confirmButton = {
                                TextButton({
                                    scope.launch {
                                        if (action { models.library.delete(overlay.id) }) {
                                            finish(overlay)
                                            if (
                                                navigation.state.value.current ==
                                                    AppRoute.Playlist(overlay.id)
                                            )
                                                navigation.back()
                                        }
                                    }
                                }) {
                                    Text("Удалить")
                                }
                            },
                            dismissButton = { TextButton({ finish(overlay) }) { Text("Отмена") } },
                        )
                    else -> Unit
                }
                edit?.let { request ->
                    PlaylistEditDialog(
                        request.name,
                        request.id != null,
                        busyEdit == request.requestId,
                        { name ->
                            scope.launch {
                                busyEdit = request.requestId
                                try {
                                    if (request.id != null) {
                                        if (
                                            action { models.library.rename(request.id, name) } &&
                                                edit?.requestId == request.requestId
                                        )
                                            edit = null
                                    } else
                                        when (val result = models.library.create(name)) {
                                            is AppResult.Failure ->
                                                snackbar.showSnackbar(present(result.error).message)
                                            is AppResult.Success ->
                                                if (edit?.requestId == request.requestId) {
                                                    edit = null
                                                    if (request.track != null) {
                                                        if (
                                                            action {
                                                                models.actions.append(
                                                                    result.value,
                                                                    request.track,
                                                                )
                                                            }
                                                        ) {
                                                            val expected =
                                                                AppOverlay.PlaylistPicker(
                                                                    request.track.id
                                                                )
                                                            finish(expected)
                                                        }
                                                    } else
                                                        navigation.push(
                                                            AppRoute.Playlist(result.value)
                                                        )
                                                }
                                        }
                                } finally {
                                    if (busyEdit == request.requestId) busyEdit = null
                                }
                            }
                        },
                        { edit = null },
                    )
                }
            }
        }
    }
}
