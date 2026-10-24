package app.musicplayer.ui.theme

import app.musicplayer.appearance.AppTheme
import kotlin.math.roundToInt

object AppThemePalette {
    fun colors(theme: AppTheme, sampled: ArtworkColors): ArtworkColors =
        when (theme) {
            AppTheme.DARK -> sampled
            AppTheme.CLASSIC ->
                ArtworkColors(
                    background = 0xff211812.toInt(),
                    surface = 0xff30231b.toInt(),
                    accent = blend(0xffdfbc96.toInt(), sampled.accent, 0.08),
                    onBackground = 0xfffff4e7.toInt(),
                    onSurface = 0xfffff4e7.toInt(),
                    onAccent = 0xff24170e.toInt(),
                )
            AppTheme.LIGHT ->
                ArtworkColors(
                    background = 0xfffaf7f2.toInt(),
                    surface = 0xffeee9e2.toInt(),
                    accent = blend(0xff67412d.toInt(), sampled.accent, 0.08),
                    onBackground = 0xff231e19.toInt(),
                    onSurface = 0xff231e19.toInt(),
                    onAccent = 0xffffffff.toInt(),
                )
        }

    fun ambientTop(theme: AppTheme, colors: ArtworkColors): Int =
        blend(colors.background, colors.accent, if (theme == AppTheme.LIGHT) 0.035 else 0.07)

    private fun blend(first: Int, second: Int, amount: Double): Int =
        0xff000000.toInt() or
            (0..2).fold(0) { value, channel ->
                val shift = channel * 8
                val a = (first ushr shift) and 255
                val b = (second ushr shift) and 255
                value or ((a * (1 - amount) + b * amount).roundToInt() shl shift)
            }
}
