package app.musicplayer.downloads

import java.io.IOException

/** Reliable admission evidence only; local extent and Media3 EOF metadata are never inputs. */
internal fun admittedExpectedBytes(assetBytes: Long?, stampBytes: Long?): Long? {
    if (stampBytes?.let { it <= 0 } == true ||
        assetBytes?.let { it <= 0 || it != stampBytes } == true) {
        throw IOException("Invalid admitted media length")
    }
    return stampBytes
}

internal fun supportedAudioTrack(declared: String, actual: String): Boolean {
    val container = declared.substringBefore(';').trim().lowercase()
    return when (container) {
        "audio/wav", "audio/wave", "audio/x-wav" -> actual == "audio/raw"
        "audio/mp4", "audio/x-m4a" -> actual in setOf("audio/mp4a-latm", "audio/alac")
        "audio/ogg", "application/ogg" -> actual in setOf("audio/vorbis", "audio/opus", "audio/flac")
        "audio/webm" -> actual in setOf("audio/vorbis", "audio/opus")
        "audio/mpeg" -> actual == "audio/mpeg"
        "audio/aac", "audio/aac-adts" -> actual == "audio/mp4a-latm"
        "audio/flac", "audio/x-flac" -> actual == "audio/flac"
        "audio/raw" -> actual == "audio/raw"
        else -> false
    }
}

/** EBML header envelope only, leaving all media parsing to Android's extractor/codec. */
internal fun hasWebmDocType(header: ByteArray): Boolean {
    var position = 0
    fun invalid(): Nothing = throw IOException("Invalid WebM header")
    fun vint(identifier: Boolean): Long {
        if (position >= header.size) invalid()
        val first = header[position++].toInt() and 0xff
        var marker = 0x80
        var width = 1
        while (first and marker == 0) {
            marker = marker ushr 1
            if (++width > if (identifier) 4 else 8) invalid()
        }
        if (width - 1 > header.size - position) invalid()
        var value = (if (identifier) first else first and (marker - 1)).toLong()
        repeat(width - 1) { value = (value shl 8) or (header[position++].toLong() and 0xff) }
        if (!identifier && value == (1L shl (7 * width)) - 1) invalid() // Unknown-size header/child.
        return value
    }
    return try {
        if (vint(true) != 0x1a45dfa3L) invalid()
        val size = vint(false)
        if (size > header.size - position) invalid()
        val end = position + size.toInt()
        var found = false
        while (position < end) {
            val id = vint(true)
            val length = vint(false)
            if (position > end || length > end - position) invalid()
            if (id == 0x4282L) { // EBML DocType, not a codec sample MIME.
                if (found || length != 4L || header[position] != 'w'.code.toByte() ||
                    header[position + 1] != 'e'.code.toByte() || header[position + 2] != 'b'.code.toByte() ||
                    header[position + 3] != 'm'.code.toByte()) invalid()
                found = true
            }
            position += length.toInt()
        }
        found
    } catch (_: IOException) { false }
}
