package app.musicplayer.ui.theme

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import app.musicplayer.appearance.AppTheme
import app.musicplayer.model.*
import kotlin.math.roundToInt
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AppThemePaletteTest {
    private val covers =
        listOf(
            0xff000000.toInt(),
            0xffffffff.toInt(),
            0xffff0000.toInt(),
            0xff0000ff.toInt(),
            0xff00ff00.toInt(),
        )

    @Test
    fun darkBackgroundAndSurfaceStayNeutralAcrossArtwork() {
        for (cover in covers) {
            val colors = AppThemePalette.colors(AppTheme.DARK, ArtworkPalette.fromPixels(IntArray(32) { cover }))
            for (color in listOf(colors.background, colors.surface, AppThemePalette.ambientTop(AppTheme.DARK, colors))) {
                val channels = listOf(channel(color, 16), channel(color, 8), channel(color, 0))
                assertTrue(channels.max() <= 30 && channels.max() - channels.min() <= 3, "Dark surfaces should stay neutral and near black")
            }
        }
    }

    @Test
    fun themePalettesRemainReadableOverActualAmbient() {
        for (theme in AppTheme.entries) for (cover in covers) {
            val colors =
                AppThemePalette.colors(theme, ArtworkPalette.fromPixels(IntArray(32) { cover }))
            val top = AppThemePalette.ambientTop(theme, colors)
            for (step in 0..20) {
                val position = step / 20.0
                val background =
                    if (position <= 0.52) blend(top, colors.surface, position / 0.52)
                    else blend(colors.surface, colors.background, (position - 0.52) / 0.48)
                assertContrast(colors.onBackground, background, 4.5, "$theme primary $step")
                assertContrast(
                    blend(background, colors.onBackground, 0.74),
                    background,
                    4.5,
                    "$theme secondary $step",
                )
                assertContrast(colors.accent, background, 3.0, "$theme control $step")
            }
            assertContrast(colors.onSurface, colors.surface, 4.5, "$theme surface")
            assertContrast(colors.onAccent, colors.accent, 4.5, "$theme accent")
        }
    }

    @Test
    fun classicStaysWarmAndLightStaysLightForMonochromeAndSaturatedArt() {
        for (cover in covers) {
            val sampled = ArtworkPalette.fromPixels(IntArray(32) { cover })
            val classic = AppThemePalette.colors(AppTheme.CLASSIC, sampled)
            val light = AppThemePalette.colors(AppTheme.LIGHT, sampled)
            val dark = AppThemePalette.colors(AppTheme.DARK, sampled)
            for (color in
                listOf(
                    classic.background,
                    classic.surface,
                    AppThemePalette.ambientTop(AppTheme.CLASSIC, classic),
                )) {
                assertTrue(
                    channel(color, 16) > channel(color, 0),
                    "CLASSIC must retain a warm base",
                )
            }
            for (color in
                listOf(
                    light.background,
                    light.surface,
                    AppThemePalette.ambientTop(AppTheme.LIGHT, light),
                )) {
                assertTrue(
                    (0..2).all { channel(color, it * 8) >= 210 },
                    "LIGHT must retain a light base",
                )
            }
            assertTrue(
                (0..2).all { channel(dark.background, it * 8) <= 32 },
                "DARK must retain a near-black base",
            )
        }
    }

    @Test
    fun changingThemeDoesNotRestartSampler() = runTest {
        var fetches = 0
        val artwork = Artwork(ArtworkKind.RELEASE, "https://art/theme", null)
        val sampler: suspend (Artwork) -> AppResult<IntArray> = {
            fetches++
            AppResult.Success(IntArray(32) { 0xff2255ee.toInt() })
        }
        val theme = mutableStateOf(AppTheme.DARK)
        var rendered: ArtworkColors? = null
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(UnitApplier(), recomposer)
        try {
            composition.setContent {
                CompositionLocalProvider(
                    LocalAppTheme provides theme.value,
                    LocalArtworkSampler provides sampler,
                ) {
                    rendered = rememberArtworkColors(artwork)
                }
            }
            runCurrent()
            Snapshot.sendApplyNotifications()
            runCurrent()
            clock.sendFrame(1L)
            runCurrent()
            val dark = rendered
            assertEquals(1, fetches)
            theme.value = AppTheme.LIGHT
            Snapshot.sendApplyNotifications()
            runCurrent()
            clock.sendFrame(2L)
            runCurrent()
            assertEquals(1, fetches)
            assertNotEquals(dark, rendered)
            assertTrue(channel(assertNotNull(rendered).background, 16) >= 210)
        } finally {
            composition.dispose()
            recomposer.close()
            runner.cancel()
        }
    }

    private class UnitApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit

        override fun insertBottomUp(index: Int, instance: Unit) = Unit

        override fun remove(index: Int, count: Int) = Unit

        override fun move(from: Int, to: Int, count: Int) = Unit

        override fun onClear() = Unit
    }

    private fun assertContrast(foreground: Int, background: Int, minimum: Double, label: String) {
        assertTrue(
            ArtworkPalette.contrast(foreground, background) >= minimum,
            "$label must reach $minimum contrast",
        )
    }

    private fun channel(color: Int, shift: Int) = (color ushr shift) and 255

    private fun blend(first: Int, second: Int, amount: Double): Int =
        0xff000000.toInt() or
            (0..2).fold(0) { value, channel ->
                value or
                    ((channel(first, channel * 8) * (1 - amount) +
                            channel(second, channel * 8) * amount)
                        .roundToInt() shl (channel * 8))
            }
}
