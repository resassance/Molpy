package app.musicplayer.ui.navigation

import app.musicplayer.collections.PlaylistId
import kotlin.test.*

class AppNavigationTest {
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
