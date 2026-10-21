package app.musicplayer.ui.theme

import kotlin.math.pow
import kotlin.math.roundToInt

data class ArtworkColors(
    val background: Int,
    val surface: Int,
    val accent: Int,
    val onBackground: Int,
    val onSurface: Int,
    val onAccent: Int,
)

object ArtworkPalette {
    fun fromPixels(argb: IntArray): ArtworkColors {
        val groups =
            argb
                .filter { pixel ->
                    val channels = channels(pixel)
                    pixel ushr 24 >= 128 &&
                        channels.max() - channels.min() >= 24 &&
                        channels.max() > 35
                }
                .groupBy { pixel ->
                    val c = channels(pixel)
                    (c[0] / 32 shl 6) + (c[1] / 32 shl 3) + c[2] / 32
                }
        val cluster = groups.entries.sortedBy { it.key }.maxByOrNull { it.value.size }?.value
        val hue =
            if (cluster.isNullOrEmpty()) 0xffa579a4.toInt()
            else
                rgb(
                    (0..2).map { index ->
                        cluster.sumOf { channels(it)[index].toLong() }.div(cluster.size).toInt()
                    }
                )
        val background = blend(0xff100c12.toInt(), hue, 0.07)
        val surface = blend(0xff1b141e.toInt(), hue, 0.10)
        val accent = blend(hue, 0xffffffff.toInt(), 0.55)
        val foreground = 0xfffaf7fa.toInt()
        val onAccent =
            if (contrast(0xff100c12.toInt(), accent) >= 4.5) 0xff100c12.toInt()
            else 0xffffffff.toInt()
        return ArtworkColors(background, surface, accent, foreground, foreground, onAccent)
    }

    fun contrast(first: Int, second: Int): Double {
        fun luminance(color: Int): Double =
            channels(color)
                .map { channel ->
                    val c = channel / 255.0
                    if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
                }
                .let { it[0] * 0.2126 + it[1] * 0.7152 + it[2] * 0.0722 }
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun channels(color: Int) =
        listOf((color ushr 16) and 255, (color ushr 8) and 255, color and 255)

    private fun rgb(c: List<Int>) = 0xff000000.toInt() or (c[0] shl 16) or (c[1] shl 8) or c[2]

    private fun blend(a: Int, b: Int, amount: Double): Int {
        val first = channels(a)
        val second = channels(b)
        return rgb((0..2).map { (first[it] * (1 - amount) + second[it] * amount).roundToInt() })
    }
}
