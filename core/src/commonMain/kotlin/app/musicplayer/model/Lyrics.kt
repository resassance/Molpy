package app.musicplayer.model

data class LyricLine(val text: String, val startMs: Long, val endMs: Long?)
enum class LyricsKind { PLAIN, LINE_SYNCED, INSTRUMENTAL }
data class NormalizedLyrics(
    val plainText: String?,
    val lines: List<LyricLine>,
    val kind: LyricsKind,
    val source: SourceRef,
)

sealed interface LyricsLookup {
    data class Available(val lyrics: NormalizedLyrics) : LyricsLookup
    data class Instrumental(val source: SourceRef) : LyricsLookup
    data object Unavailable : LyricsLookup
    data class Failed(val error: AppError) : LyricsLookup
}
