package app.musicplayer.ui.theme

import kotlin.test.*

class ArtworkPaletteTest {
    @Test
    fun extremeAndSaturatedCoversKeepTextAndControlsReadable() {
        val samples =
            listOf(
                0xff000000.toInt(),
                0xffffffff.toInt(),
                0xffff0000.toInt(),
                0xff0000ff.toInt(),
                0xff00ff00.toInt(),
                0x00ff0000,
            )
        for (sample in samples) {
            val c = ArtworkPalette.fromPixels(IntArray(1024) { sample })
            assertTrue(ArtworkPalette.contrast(c.onBackground, c.background) >= 4.5)
            assertTrue(ArtworkPalette.contrast(c.onSurface, c.surface) >= 4.5)
            assertTrue(ArtworkPalette.contrast(c.onAccent, c.accent) >= 4.5)
            assertTrue(ArtworkPalette.contrast(c.accent, c.surface) >= 3.0)
        }
        assertEquals(
            ArtworkPalette.fromPixels(intArrayOf()),
            ArtworkPalette.fromPixels(IntArray(10) { 0xffffffff.toInt() }),
        )
    }

    @Test
    fun paletteDependsOnCoverAndIsDeterministic() {
        val red = IntArray(1024) { 0xffe33232.toInt() }
        val blue = IntArray(1024) { 0xff3154d4.toInt() }
        assertNotEquals(
            ArtworkPalette.fromPixels(red).accent,
            ArtworkPalette.fromPixels(blue).accent,
        )
        assertEquals(ArtworkPalette.fromPixels(red), ArtworkPalette.fromPixels(red.reversedArray()))
    }
}
