package app.musicplayer.downloads

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun downloadRangeUri(uri: String, offset: Long, length: Long, total: Long?): String {
    val url = uri.toHttpUrlOrNull() ?: return uri
    if (
        url.scheme != "https" ||
            !(url.host == "googlevideo.com" || url.host.endsWith(".googlevideo.com"))
    )
        return uri
    if (
        total == null || total <= 0 || offset < 0 || offset >= total || length == 0L || length < -1L
    )
        return uri
    if (
        listOf("sparams", "lsparams").any { key ->
            url.queryParameterValues(key).any { it?.split(',')?.contains("range") == true }
        }
    )
        return uri
    val remaining = total - offset
    val count = if (length < 0) remaining else minOf(length, remaining)
    val end = offset + count - 1
    return url.newBuilder()
        .removeAllQueryParameters("range")
        .addQueryParameter("range", "$offset-$end")
        .build()
        .toString()
}

internal fun downloadProbeLength(
    status: Int,
    contentRange: String?,
    contentLength: String?,
): Long? {
    if (status != 206 || contentLength?.toLongOrNull() != 1L) return null
    val match = Regex("bytes 0-0/([0-9]+)").matchEntire(contentRange ?: "") ?: return null
    return match.groupValues[1].toLongOrNull()?.takeIf { it > 0 }
}
