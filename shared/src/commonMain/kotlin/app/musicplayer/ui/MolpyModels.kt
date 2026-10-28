package app.musicplayer.ui

import app.musicplayer.artists.*
import app.musicplayer.model.*
import app.musicplayer.ui.artists.*
import app.musicplayer.ui.catalog.CatalogModel
import app.musicplayer.ui.catalog.PublicPlaylistModel
import app.musicplayer.ui.components.TrackActions
import app.musicplayer.ui.downloads.DownloadsModel
import app.musicplayer.ui.library.LibraryModel
import app.musicplayer.ui.navigation.*
import app.musicplayer.ui.player.PlayerModel
import app.musicplayer.ui.profile.ProfileModel
import app.musicplayer.ui.search.SearchModel
import app.musicplayer.ui.settings.AppearanceModel
import app.musicplayer.ui.settings.NetworkSettingsModel
import app.musicplayer.ui.settings.PlaybackSettingsModel
import app.musicplayer.ui.theme.PaletteModel
import kotlinx.coroutines.flow.*

class MolpyModels(
    val player: PlayerModel,
    val search: SearchModel,
    val library: LibraryModel,
    val catalog: CatalogModel,
    val profile: ProfileModel,
    val downloads: DownloadsModel,
    val settings: NetworkSettingsModel,
    val palette: PaletteModel,
    val actions: TrackActions,
    val playbackSettings: PlaybackSettingsModel? = null,
    val artists: ArtistDirectoryModel? = null,
    val artistEditor: ArtistEditorModel? = null,
    val artistMerge: ArtistMergeModel? = null,
    val artistRepository: ArtistRepository? = null,
    val publicPlaylist: PublicPlaylistModel? = null,
    val sampleArtworkPixels: (suspend (Artwork) -> AppResult<IntArray>)? = null,
    val appearance: AppearanceModel? = null,
    val artistArtwork: ArtistArtworkCoordinator? = null,
) {
    private val epoch = MutableStateFlow(0L)
    val artworkEpoch: StateFlow<Long> = epoch.asStateFlow()

    fun networkInvalidated() {
        artistArtwork?.networkInvalidated()
        player.networkInvalidated()
        search.networkInvalidated()
        library.networkInvalidated()
        profile.networkInvalidated()
        catalog.networkInvalidated()
        publicPlaylist?.networkInvalidated()
        artistEditor?.networkInvalidated()
    }

    fun networkActivated() {
        artistArtwork?.networkActivated()
        epoch.value++
        library.refreshMetadata()
        catalog.refreshMetadata()
        player.refreshMetadata()
        profile.refreshMetadata()
        publicPlaylist?.networkActivated()
        player.loadLyrics()
        val track = player.state.value.currentTrack
        palette.update(track?.id, track?.artwork)
    }

    fun setMetadataRoute(route: AppRoute?) {
        catalog.setMetadataVisible(route is AppRoute.Album || route is AppRoute.Artist)
        publicPlaylist?.setMetadataVisible(route is AppRoute.PublicPlaylist)
        library.setMetadataVisible(
            (route as? AppRoute.Root)?.tab == AppTab.LIBRARY ||
                route is AppRoute.Playlist ||
                route in
                    setOf<AppRoute>(
                        AppRoute.Favorites,
                        AppRoute.Playlists,
                        AppRoute.Albums,
                        AppRoute.Artists,
                    )
        )
        profile.setMetadataVisible(
            (route as? AppRoute.Root)?.tab == AppTab.PROFILE || route == AppRoute.History
        )
    }

    fun close() {
        appearance?.close()
        artistArtwork?.close()
        artistEditor?.close()
        artistMerge?.close()
        artists?.close()
        player.close()
        search.close()
        library.close()
        catalog.close()
        publicPlaylist?.close()
        profile.close()
        downloads.close()
        settings.close()
        playbackSettings?.close()
        palette.close()
    }
}
