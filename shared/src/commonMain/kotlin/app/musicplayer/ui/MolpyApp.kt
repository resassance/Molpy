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
import app.musicplayer.appearance.AppTheme
import app.musicplayer.artists.*
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.collections.*
import app.musicplayer.model.*
import app.musicplayer.ui.artists.*
import app.musicplayer.ui.components.*
import app.musicplayer.ui.library.*
import app.musicplayer.ui.navigation.*
import app.musicplayer.ui.player.*
import app.musicplayer.ui.settings.AppearanceState
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
    val appearance =
        models.appearance?.state?.collectAsState()?.value
            ?: AppearanceState(theme = AppTheme.DARK, initialized = true)
    if (!appearance.initialized) {
        Box(Modifier.fillMaxSize().background(Color(0xff171717)))
        return
    }
    val player by models.player.state.collectAsState()
    val library by models.library.state.collectAsState()
    val downloads by models.downloads.state.collectAsState()
    val nav by navigation.state.collectAsState()
    val sampled by models.palette.state.collectAsState()
    val colors = AppThemePalette.colors(appearance.theme, sampled)
    val epoch by models.artworkEpoch.collectAsState()
    val scope = rememberCoroutineScope()
    val saved = rememberSaveableStateHolder()
    val keyboard = LocalSoftwareKeyboardController.current
    val ime = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val snackbar = remember { SnackbarHostState() }
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    var edit by remember { mutableStateOf<PlaylistEdit?>(null) }
    var busyEdit by remember { mutableStateOf<String?>(null) }
    val artistDirectory = models.artists?.state?.collectAsState()?.value?.data ?: ArtistDirectory()
    val artistEditor = models.artistEditor?.state?.collectAsState()?.value ?: ArtistEditorState()
    val artistMerge = models.artistMerge?.state?.collectAsState()?.value ?: ArtistMergeState()
    var artistTrack by remember { mutableStateOf<Track?>(null) }
    var ensuredArtists by remember { mutableStateOf<TrackPresentation?>(null) }
    var artistOpenGeneration by remember { mutableLongStateOf(0) }
    var mergeQuery by remember { mutableStateOf("") }
    LaunchedEffect(nav.overlays) {
        if (nav.overlays.none { it is AppOverlay.ArtistEditor }) models.artistEditor?.closeDraft()
        if (AppOverlay.ArtistMerge !in nav.overlays) models.artistMerge?.closeDraft()
        if (nav.overlays.lastOrNull() is AppOverlay.ArtistMergePicker) mergeQuery = ""
    }
    LaunchedEffect(artistEditor.saved) {
        if (artistEditor.saved && nav.overlays.lastOrNull() is AppOverlay.ArtistEditor)
            navigation.back()
    }
    LaunchedEffect(artistMerge) {
        if (
            nav.overlays.lastOrNull() == AppOverlay.ArtistMerge &&
                !artistMerge.saving &&
                artistMerge.merge == null &&
                artistMerge.split == null &&
                artistMerge.error == null
        )
            navigation.back()
    }
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
    fun openArtist(credit: ArtistCredit) {
        credit.id?.let { id ->
            navigation.push(AppRoute.Artist(ArtistRef(credit.name, credit.source), id.value, id))
        }
    }
    fun openTrackArtists(value: Track) {
        val repository = models.artistRepository ?: return
        val selected = ++artistOpenGeneration
        val expected = navigation.state.value
        scope.launch {
            val result = repository.ensure(value)
            ensureActive()
            if (selected != artistOpenGeneration || navigation.state.value != expected)
                return@launch
            when (result) {
                is AppResult.Failure -> snackbar.showSnackbar(present(result.error).message)
                is AppResult.Success -> {
                    val credits = result.value.artists
                    if (credits.size == 1) openArtist(credits.single())
                    else if (credits.isNotEmpty()) {
                        artistTrack = value
                        ensuredArtists = result.value
                        navigation.open(AppOverlay.ArtistSelection(value.id))
                    } else
                        snackbar.showSnackbar(
                            "Исполнитель не указан. Его можно добавить в меню трека."
                        )
                }
            }
        }
    }
    MolpyTheme(appearance.theme, colors) {
        CompositionLocalProvider(
            LocalArtistArtworkCoordinator provides models.artistArtwork,
            LocalArtworkEpoch provides epoch,
            LocalArtistDirectory provides artistDirectory,
            LocalArtworkSampler provides models.sampleArtworkPixels,
        ) {
            Box(Modifier.fillMaxSize()) {
                ArtworkAmbientBackground(colors, Modifier.matchParentSize())
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
                                MolpyBottomNavigation(
                                    nav.selectedTab,
                                    { tab ->
                                        keyboard?.hide()
                                        navigation.select(tab)
                                    },
                                )
                            }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        CompositionLocalProvider(
                            LocalArtistArtworkViewport provides
                                ArtistArtworkViewport(
                                    active = nav.overlays.isEmpty() && edit == null
                                )
                        ) {
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
                }
                CompositionLocalProvider(
                    LocalArtistArtworkViewport provides
                        ArtistArtworkViewport(
                            active =
                                nav.overlays.lastOrNull() == AppOverlay.NowPlaying && edit == null
                        )
                ) {
                    val currentDownload = track?.let { selectDownload(it, player.snapshot) }
                    val hasDownload =
                        track != null && downloads.assets.any { it.trackId == track.id }
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
                        visible = AppOverlay.NowPlaying in nav.overlays,
                        gesturesEnabled = nav.overlays.lastOrNull() == AppOverlay.NowPlaying,
                        onArtists =
                            if (models.artistRepository == null) null else ::openTrackArtists,
                        onPlaylist =
                            track?.let { value ->
                                {
                                    menuTrack = value
                                    navigation.open(AppOverlay.PlaylistPicker(value.id))
                                }
                            },
                        onDownload =
                            track?.let {
                                {
                                    if (hasDownload) {
                                        navigation.select(AppTab.LIBRARY)
                                        navigation.push(AppRoute.Downloads)
                                    } else {
                                        currentDownload?.let { chosen ->
                                            scope.launch {
                                                action {
                                                    models.actions.download(
                                                        chosen.track,
                                                        chosen.candidate,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                        downloadLabel = if (hasDownload) "Загрузки" else "Скачать",
                        downloadEnabled = hasDownload || currentDownload != null,
                    )
                }
                CompositionLocalProvider(
                    LocalArtistArtworkViewport provides ArtistArtworkViewport(active = edit == null)
                ) {
                    when (val overlay = nav.overlays.lastOrNull()) {
                        is AppOverlay.ArtistEditor ->
                            if (artistEditor.track?.id == overlay.trackId) {
                                ArtistEditorSheet(
                                    artistEditor,
                                    artistDirectory,
                                    { index ->
                                        models.artistEditor?.setQuery("")
                                        navigation.open(
                                            AppOverlay.ArtistPicker(overlay.trackId, index)
                                        )
                                    },
                                    {
                                        models.artistEditor?.setQuery("")
                                        navigation.open(
                                            AppOverlay.ArtistPicker(overlay.trackId, null)
                                        )
                                    },
                                    { models.artistEditor?.remove(it) },
                                    { models.artistEditor?.save() },
                                    { models.artistEditor?.reset() },
                                    {
                                        models.artistEditor?.closeDraft()
                                        finish(overlay)
                                    },
                                )
                            }
                        is AppOverlay.ArtistPicker ->
                            ArtistPickerSheet(
                                artistEditor.query,
                                artistEditor.results,
                                artistDirectory,
                                { models.artistEditor?.setQuery(it) },
                                { choice ->
                                    if (overlay.index == null) models.artistEditor?.add(choice)
                                    else models.artistEditor?.select(overlay.index, choice)
                                    models.artistEditor?.setQuery("")
                                    finish(overlay)
                                },
                                {
                                    models.artistEditor?.setQuery("")
                                    finish(overlay)
                                },
                                artwork = artwork,
                            )
                        is AppOverlay.ArtistSelection ->
                            artistTrack
                                ?.takeIf { it.id == overlay.trackId }
                                ?.let { value ->
                                    val presentation = trackPresentation(value)
                                    val credits =
                                        if (
                                            presentation.revision >= (ensuredArtists?.revision ?: 0)
                                        )
                                            presentation.artists
                                        else ensuredArtists?.artists.orEmpty()
                                    ArtistSelectionSheet(credits, ::openArtist, { finish(overlay) })
                                }
                        is AppOverlay.ArtistMergePicker ->
                            ArtistPickerSheet(
                                mergeQuery,
                                LoadState(emptyList()),
                                artistDirectory,
                                { mergeQuery = it },
                                { choice ->
                                    if (choice is ArtistChoice.Existing) {
                                        models.artistMerge?.preview(
                                            overlay.artistId,
                                            choice.id,
                                            null,
                                        )
                                        finish(overlay)
                                        navigation.open(AppOverlay.ArtistMerge)
                                    }
                                },
                                { finish(overlay) },
                                excluded = setOf(overlay.artistId),
                                allowName = false,
                                title = "Объединить с…",
                            )
                        is AppOverlay.ArtistMerges ->
                            ArtistMergesSheet(
                                overlay.artistId,
                                artistDirectory,
                                { id ->
                                    models.artistMerge?.previewSplit(id)
                                    navigation.open(AppOverlay.ArtistMerge)
                                },
                                { finish(overlay) },
                            )
                        AppOverlay.ArtistMerge ->
                            ArtistMergeSheet(
                                artistMerge,
                                artistDirectory,
                                {
                                    if (artistMerge.split != null)
                                        models.artistMerge?.confirmSplit()
                                    else models.artistMerge?.confirm()
                                },
                                {
                                    models.artistMerge?.closeDraft()
                                    finish(overlay)
                                },
                                { source ->
                                    artistMerge.merge?.let {
                                        models.artistMerge?.preview(it.origin, it.target, source)
                                    }
                                },
                            )
                        AppOverlay.Queue ->
                            QueueSheet(
                                player,
                                models.player,
                                artwork,
                                onOpenPublicPlaylist = {
                                    models.publicPlaylist?.refresh(it)
                                    navigation.push(AppRoute.PublicPlaylist(it))
                                },
                                onClose = { navigation.back() },
                            )
                        AppOverlay.Lyrics ->
                            LyricsScreen(
                                state = player,
                                artwork = artwork,
                                onClose = { navigation.back() },
                                onRetry = models.player::loadLyrics,
                                onPlayPause = models.player::playPause,
                                onNext = models.player::next,
                                onSeek = models.player::seek,
                            )
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
                                                        navigation.state.value.overlays
                                                            .lastOrNull() == overlay
                                                    finish(overlay)
                                                    if (
                                                        stillHere &&
                                                            models.player.state.value
                                                                .currentTrack == null
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
                                        onPlayNext = {
                                            scope.launch {
                                                if (
                                                    action {
                                                        models.actions.enqueue(
                                                            value,
                                                            app.musicplayer.queue.QueueInsertion
                                                                .NEXT,
                                                        )
                                                    }
                                                )
                                                    finish(overlay)
                                            }
                                        },
                                        onEditArtists =
                                            if (models.artistEditor == null) null
                                            else {
                                                {
                                                    finish(overlay)
                                                    models.artistEditor.open(value)
                                                    navigation.open(
                                                        AppOverlay.ArtistEditor(value.id)
                                                    )
                                                }
                                            },
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
                            MolpyDialog(
                                onDismissRequest = { finish(overlay) },
                                title = "Удалить плейлист?",
                                icon = AppIcons.Trash,
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
                                dismissButton = {
                                    TextButton({ finish(overlay) }) { Text("Отмена") }
                                },
                            )
                        else -> Unit
                    }
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
