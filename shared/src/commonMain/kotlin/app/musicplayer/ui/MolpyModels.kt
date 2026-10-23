package app.musicplayer.ui

import app.musicplayer.artists.*
import app.musicplayer.ui.artists.*
import app.musicplayer.ui.catalog.CatalogModel
import app.musicplayer.ui.catalog.PublicPlaylistModel
import app.musicplayer.ui.components.TrackActions
import app.musicplayer.ui.downloads.DownloadsModel
import app.musicplayer.ui.library.LibraryModel
import app.musicplayer.ui.player.PlayerModel
import app.musicplayer.ui.profile.ProfileModel
import app.musicplayer.ui.search.SearchModel
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
) {
    private val epoch = MutableStateFlow(0L)
    val artworkEpoch: StateFlow<Long> = epoch.asStateFlow()

    fun networkInvalidated() {
        player.networkInvalidated()
        search.networkInvalidated()
        catalog.networkInvalidated()
        publicPlaylist?.networkInvalidated()
        artistEditor?.networkInvalidated()
    }

    fun networkActivated() {
        epoch.value++
        publicPlaylist?.networkActivated()
        player.loadLyrics()
        val track = player.state.value.currentTrack
        palette.update(track?.id, track?.artwork)
    }

    fun close() {
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
