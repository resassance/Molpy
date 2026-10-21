package app.musicplayer.ui.player

import app.musicplayer.model.*

object LyricsPosition {
    fun activeLine(lyrics: NormalizedLyrics, positionMs: Long): Int? {
        if (lyrics.kind != LyricsKind.LINE_SYNCED) return null
        val index = lyrics.lines.indexOfLast { it.startMs <= positionMs }
        if (index < 0) return null
        val end = lyrics.lines[index].endMs
        return index.takeIf { end == null || positionMs < end }
    }
}
