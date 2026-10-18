package app.musicplayer.lyrics

import app.musicplayer.model.*

class LyricsSelector {
    fun select(candidates: List<LyricsCandidate>): NormalizedLyrics? = candidates.asSequence()
        .filter { it.versionMatched }
        .mapNotNull { candidate ->
            val lyrics = candidate.lyrics
            when {
                lyrics.kind == LyricsKind.INSTRUMENTAL -> lyrics.copy(plainText = null, lines = emptyList())
                lyrics.kind == LyricsKind.LINE_SYNCED && candidate.timingValid && lyrics.lines.isNotEmpty() -> lyrics
                else -> (lyrics.plainText?.takeIf { it.isNotBlank() }
                    ?: lyrics.lines.joinToString("\n") { it.text }.takeIf { it.isNotBlank() })
                    ?.let { lyrics.copy(plainText = it, lines = emptyList(), kind = LyricsKind.PLAIN) }
            }
        }
        .maxByOrNull { when (it.kind) { LyricsKind.LINE_SYNCED -> 3; LyricsKind.PLAIN -> 2; LyricsKind.INSTRUMENTAL -> 1 } }
}
