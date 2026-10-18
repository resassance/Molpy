package app.musicplayer.playback

import androidx.media3.common.MimeTypes
import app.musicplayer.model.TransportKind

/** Media3's MIME dispatch is exact; normalized transport owns adaptive source selection. */
internal fun media3MimeType(transport: TransportKind, representationMime: String): String =
    if (transport == TransportKind.HLS) MimeTypes.APPLICATION_M3U8 else representationMime
