package app.musicplayer.ui.navigation

import app.musicplayer.artists.ArtistId
import app.musicplayer.collections.PlaylistId
import app.musicplayer.model.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

enum class AppTab(val label: String) {
    LIBRARY("Медиатека"),
    SEARCH("Поиск"),
    PROFILE("Профиль"),
}

sealed interface AppRoute {
    data class Root(val tab: AppTab) : AppRoute

    data object Favorites : AppRoute

    data object Playlists : AppRoute

    data class Playlist(val id: PlaylistId) : AppRoute

    data object Albums : AppRoute

    data object Artists : AppRoute

    data class Album(val ref: AlbumRef, val localKey: String? = null) : AppRoute

    data class Artist(
        val ref: ArtistRef,
        val localKey: String? = null,
        val artistId: ArtistId? = null,
    ) : AppRoute

    data object Downloads : AppRoute

    data object History : AppRoute

    data object Settings : AppRoute
}

sealed interface AppOverlay {
    data class ArtistEditor(val trackId: TrackId) : AppOverlay

    data class ArtistPicker(val trackId: TrackId, val index: Int?) : AppOverlay

    data class ArtistSelection(val trackId: TrackId) : AppOverlay

    data class ArtistMergePicker(val artistId: ArtistId) : AppOverlay

    data class ArtistMerges(val artistId: ArtistId) : AppOverlay

    data object ArtistMerge : AppOverlay

    data object NowPlaying : AppOverlay

    data object Queue : AppOverlay

    data object Lyrics : AppOverlay

    data class TrackMenu(val trackId: TrackId) : AppOverlay

    data class PlaylistPicker(val trackId: TrackId) : AppOverlay

    data class ConfirmDeletePlaylist(val id: PlaylistId) : AppOverlay
}

data class NavigationState(
    val selectedTab: AppTab = AppTab.LIBRARY,
    val stacks: Map<AppTab, List<AppRoute>> =
        AppTab.entries.associateWith { listOf(AppRoute.Root(it)) },
    val overlays: List<AppOverlay> = emptyList(),
) {
    val current: AppRoute
        get() = stacks.getValue(selectedTab).last()
}

class AppNavigation(initial: NavigationState = NavigationState()) {
    private val mutable = MutableStateFlow(initial)
    val state: StateFlow<NavigationState> = mutable.asStateFlow()

    fun select(tab: AppTab) {
        val current = mutable.value
        mutable.value =
            current.copy(
                selectedTab = tab,
                stacks =
                    if (tab == current.selectedTab)
                        current.stacks + (tab to listOf(AppRoute.Root(tab)))
                    else current.stacks,
                overlays = emptyList(),
            )
    }

    fun push(route: AppRoute) {
        if (route is AppRoute.Root) {
            select(route.tab)
            return
        }
        val s = mutable.value
        if (s.current != route)
            mutable.value =
                s.copy(
                    stacks =
                        s.stacks + (s.selectedTab to (s.stacks.getValue(s.selectedTab) + route)),
                    overlays = emptyList(),
                )
    }

    fun open(overlay: AppOverlay) {
        val s = mutable.value
        val layers =
            when (overlay) {
                AppOverlay.NowPlaying -> listOf(overlay)
                AppOverlay.Queue,
                AppOverlay.Lyrics -> listOf(AppOverlay.NowPlaying, overlay)
                else -> s.overlays.filterNot { it == overlay } + overlay
            }
        mutable.value = s.copy(overlays = layers)
    }

    fun back(): Boolean {
        val s = mutable.value
        if (s.overlays.isNotEmpty()) {
            mutable.value = s.copy(overlays = s.overlays.dropLast(1))
            return true
        }
        val stack = s.stacks.getValue(s.selectedTab)
        if (stack.size <= 1) return false
        mutable.value = s.copy(stacks = s.stacks + (s.selectedTab to stack.dropLast(1)))
        return true
    }

    fun save(): List<String> =
        listOf(state.value.selectedTab.name) +
            AppTab.entries.map { tab ->
                JsonArray(state.value.stacks.getValue(tab).map(::encode)).toString()
            }

    companion object {
        fun restore(saved: List<String>): AppNavigation =
            try {
                require(saved.size == 4)
                val selected = AppTab.valueOf(saved[0])
                val stacks =
                    AppTab.entries.associateWith { tab ->
                        Json.parseToJsonElement(saved[tab.ordinal + 1])
                            .jsonArray
                            .map(::decode)
                            .also {
                                require(it.firstOrNull() == AppRoute.Root(tab) && it.size <= 30)
                            }
                    }
                AppNavigation(NavigationState(selected, stacks))
            } catch (_: Exception) {
                AppNavigation()
            }

        private fun encode(route: AppRoute): JsonArray {
            fun entity(kind: String, name: String, source: SourceRef?, key: String?) =
                listOf(
                    kind,
                    name,
                    source?.provider.orEmpty(),
                    source?.kind.orEmpty(),
                    source?.id.orEmpty(),
                    key.orEmpty(),
                )
            val fields =
                when (route) {
                    is AppRoute.Root -> listOf("root", route.tab.name)
                    AppRoute.Favorites -> listOf("favorites")
                    AppRoute.Playlists -> listOf("playlists")
                    is AppRoute.Playlist -> listOf("playlist", route.id.value)
                    AppRoute.Albums -> listOf("albums")
                    AppRoute.Artists -> listOf("artists")
                    is AppRoute.Album ->
                        entity("album", route.ref.name, route.ref.source, route.localKey)
                    is AppRoute.Artist ->
                        entity("artist", route.ref.name, route.ref.source, route.localKey) +
                            route.artistId?.value.orEmpty()
                    AppRoute.Downloads -> listOf("downloads")
                    AppRoute.History -> listOf("history")
                    AppRoute.Settings -> listOf("settings")
                }
            return JsonArray(fields.map(::JsonPrimitive))
        }

        private fun decode(element: JsonElement): AppRoute {
            val f = element.jsonArray.map { it.jsonPrimitive.content }
            fun source() = if (f[2].isEmpty()) null else SourceRef(f[2], f[3], f[4])
            return when (f[0]) {
                "root" -> AppRoute.Root(AppTab.valueOf(f[1]))
                "favorites" -> AppRoute.Favorites
                "playlists" -> AppRoute.Playlists
                "playlist" -> AppRoute.Playlist(PlaylistId(f[1]))
                "albums" -> AppRoute.Albums
                "artists" -> AppRoute.Artists
                "album" -> AppRoute.Album(AlbumRef(f[1], source()), f[5].ifEmpty { null })
                "artist" ->
                    AppRoute.Artist(
                        ArtistRef(f[1], source()),
                        f[5].ifEmpty { null },
                        f.getOrNull(6)?.takeIf { it.isNotEmpty() }?.let(::ArtistId),
                    )
                "downloads" -> AppRoute.Downloads
                "history" -> AppRoute.History
                "settings" -> AppRoute.Settings
                else -> error("Unknown route")
            }
        }
    }
}
