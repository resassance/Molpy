package app.musicplayer.artwork

import io.ktor.http.*
import kotlin.math.roundToInt

internal fun artworkAddress(address: String, sizePx: Int): String {
    if (sizePx <= 0) return address
    val url =
        try {
            Url(address)
        } catch (_: IllegalArgumentException) {
            return address
        }
    if (url.protocol != URLProtocol.HTTPS || url.host !in artworkHosts) return address
    val path = address.substringBefore('?')
    val size = artworkSize.find(path) ?: return address
    val width =
        (size.groupValues[1].ifEmpty { size.groupValues[3] }).toIntOrNull() ?: return address
    val height =
        (size.groupValues[2].ifEmpty { size.groupValues[3] }).toIntOrNull() ?: return address
    if (width <= 0 || height <= 0) return address
    val target =
        when {
            sizePx <= 128 -> 128
            sizePx <= 320 -> 320
            sizePx <= 640 -> 640
            else -> 1280
        }
    if (maxOf(width, height) >= target) return address
    val scale = target.toDouble() / maxOf(width, height)
    val replacement =
        if (size.groupValues[3].isNotEmpty()) "=s$target"
        else
            "=w${(width * scale).roundToInt().coerceAtLeast(1)}-h${(height * scale).roundToInt().coerceAtLeast(1)}"
    return address.replaceRange(size.range, replacement)
}

private val artworkHosts =
    setOf("lh3.googleusercontent.com", "yt3.googleusercontent.com", "yt3.ggpht.com")
private val artworkSize = Regex("=(?:w(\\d+)-h(\\d+)|s(\\d+))(?=-|$)")
