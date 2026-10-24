package app.musicplayer.ui.navigation

import kotlin.test.*

class AppearanceNavigationTest {
    @Test
    fun appearanceRouteRestoresAndBackReturnsToSettingsWithoutChangingOtherStacks() {
        val nav = AppNavigation()
        nav.push(AppRoute.Downloads)
        nav.select(AppTab.PROFILE)
        nav.push(AppRoute.Settings)
        val legacy = nav.save()
        nav.push(AppRoute.Appearance)
        val restored = AppNavigation.restore(nav.save())
        assertEquals(AppRoute.Appearance, restored.state.value.current)
        assertTrue(restored.back())
        assertEquals(AppRoute.Settings, restored.state.value.current)
        assertEquals(
            legacy,
            restored.save(),
            "Existing serialized routes must remain byte-for-byte compatible",
        )
        restored.select(AppTab.LIBRARY)
        assertEquals(AppRoute.Downloads, restored.state.value.current)
        restored.select(AppTab.PROFILE)
        assertEquals(AppRoute.Settings, restored.state.value.current)
        restored.select(AppTab.PROFILE)
        assertEquals(AppRoute.Root(AppTab.PROFILE), restored.state.value.current)
    }
}
