package app.musicplayer.ui.navigation

import app.musicplayer.catalog.PublicPlaylistRef
import app.musicplayer.collections.PlaylistId
import app.musicplayer.model.*
import kotlin.test.*

class AppNavigationTest {
    @Test
    fun reopeningCurrentPublicPlaylistDismissesPlayerAndQueueOverlays() {
        val nav = AppNavigation()
        val route =
            AppRoute.PublicPlaylist(
                PublicPlaylistRef("List", SourceRef("youtube", "playlist", "PLsame"))
            )
        nav.push(route)
        nav.open(AppOverlay.Queue)
        assertEquals(2, nav.state.value.overlays.size)
        nav.push(route)
        assertTrue(nav.state.value.overlays.isEmpty())
        assertEquals(
            listOf(AppRoute.Root(AppTab.LIBRARY), route),
            nav.state.value.stacks.getValue(AppTab.LIBRARY),
        )
    }

    @Test
    fun playlistAndLocalPlaylistRoutesStayDistinct() {
        val nav = AppNavigation()
        val ref =
            PublicPlaylistRef(
                "Same / title \"name\"",
                SourceRef("youtube-music", "playlist", "same/name"),
            )
        val local = AppRoute.Playlist(PlaylistId("same/name"))
        nav.push(local)
        nav.push(AppRoute.PublicPlaylist(ref))
        val restored = AppNavigation.restore(nav.save())
        assertEquals(AppRoute.PublicPlaylist(ref), restored.state.value.current)
        assertTrue(restored.back())
        assertEquals(local, restored.state.value.current)
        nav.select(AppTab.LIBRARY)
        assertEquals(AppRoute.Root(AppTab.LIBRARY), nav.state.value.current)
    }

    @Test
    fun legacyEntityRoutesAcceptNullAndMissingOptionalFields() {
        val restored =
            AppNavigation.restore(
                listOf(
                    "LIBRARY",
                    "[[\"root\",\"LIBRARY\"],[\"album\",\"Local\",null,null,null,null],[\"artist\",\"Artist\",\"\",\"\",\"\"]]",
                    "[[\"root\",\"SEARCH\"]]",
                    "[[\"root\",\"PROFILE\"]]",
                )
            )
        assertEquals(AppRoute.Artist(ArtistRef("Artist", null)), restored.state.value.current)
        restored.back()
        assertEquals(AppRoute.Album(AlbumRef("Local", null)), restored.state.value.current)
    }

    @Test
    fun reselectingActiveTabReturnsToRootAndPreservesOtherStacks() {
        val nav = AppNavigation()
        nav.push(AppRoute.Downloads)
        nav.select(AppTab.PROFILE)
        nav.push(AppRoute.History)
        nav.select(AppTab.LIBRARY)
        assertEquals(AppRoute.Downloads, nav.state.value.current)
        nav.select(AppTab.LIBRARY)
        assertEquals(AppRoute.Root(AppTab.LIBRARY), nav.state.value.current)
        assertFalse(nav.back())
        nav.select(AppTab.PROFILE)
        assertEquals(AppRoute.History, nav.state.value.current)
        val restored = AppNavigation.restore(nav.save())
        restored.select(AppTab.LIBRARY)
        assertEquals(AppRoute.Root(AppTab.LIBRARY), restored.state.value.current)
        restored.select(AppTab.PROFILE)
        assertEquals(AppRoute.History, restored.state.value.current)
    }

    @Test
    fun tabsKeepTheirOwnStackAndBackClosesExactlyOneOverlay() {
        val nav = AppNavigation()
        assertEquals(AppTab.LIBRARY, nav.state.value.selectedTab)
        nav.push(AppRoute.Playlist(PlaylistId("mine")))
        nav.select(AppTab.SEARCH)
        nav.open(AppOverlay.NowPlaying)
        nav.open(AppOverlay.Queue)
        assertTrue(nav.back())
        assertEquals(listOf(AppOverlay.NowPlaying), nav.state.value.overlays)
        assertTrue(nav.back())
        assertFalse(nav.back())
        nav.select(AppTab.LIBRARY)
        assertEquals(AppRoute.Playlist(PlaylistId("mine")), nav.state.value.current)
        assertTrue(nav.back())
        assertFalse(nav.back())
    }

    @Test
    fun savedNavigationRestoresStableRoutesWithoutTrackObjectsOrOverlays() {
        val nav = AppNavigation()
        nav.push(AppRoute.Playlist(PlaylistId("same/name")))
        nav.select(AppTab.PROFILE)
        nav.push(AppRoute.Settings)
        nav.open(AppOverlay.NowPlaying)
        val restored = AppNavigation.restore(nav.save())
        assertEquals(AppTab.PROFILE, restored.state.value.selectedTab)
        assertEquals(AppRoute.Settings, restored.state.value.current)
        assertTrue(restored.state.value.overlays.isEmpty())
        restored.select(AppTab.LIBRARY)
        assertEquals(AppRoute.Playlist(PlaylistId("same/name")), restored.state.value.current)
        assertEquals(
            AppTab.LIBRARY,
            AppNavigation.restore(listOf("invalid")).state.value.selectedTab,
        )
    }
}
