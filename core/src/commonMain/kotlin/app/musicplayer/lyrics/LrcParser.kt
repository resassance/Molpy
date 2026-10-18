package app.musicplayer.lyrics

import app.musicplayer.model.*

/** Strict line timing. Unsupported offsets and partially timed documents cannot claim synchronization. */
class LrcParser {
    fun parse(text: String, durationMs: Long?): AppResult<List<LyricLine>> {
        if (durationMs != null && durationMs < 0) return invalid()
        val lines = mutableListOf<LyricLine>()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank() || METADATA.matches(line)) continue
            var cursor = 0
            val starts = mutableListOf<Long>()
            while (cursor < line.length && line[cursor] == '[') {
                val match = TIME.find(line, cursor)?.takeIf { it.range.first == cursor } ?: return invalid()
                val minutes = match.groupValues[1].toLongOrNull() ?: return invalid()
                val seconds = match.groupValues[2].toIntOrNull()?.takeIf { it < 60 } ?: return invalid()
                val fraction = match.groupValues[3].padEnd(3, '0').toLongOrNull() ?: 0L
                if (minutes > (Long.MAX_VALUE - 59999) / 60000) return invalid()
                val start = minutes * 60000 + seconds * 1000 + fraction
                if (durationMs != null && start > durationMs) return invalid()
                starts += start
                cursor = match.range.last + 1
            }
            if (starts.isEmpty()) return invalid()
            val words = line.substring(cursor)
            for (start in starts) {
                if (lines.lastOrNull()?.startMs?.let { start < it } == true) return invalid()
                // Repeated timestamps and repeated text are meaningful; never deduplicate them.
                lines += LyricLine(words, start, null)
            }
        }
        if (lines.isEmpty()) return invalid()
        return AppResult.Success(lines.mapIndexed { index, line ->
            line.copy(endMs = lines.getOrNull(index + 1)?.startMs ?: durationMs)
        })
    }

    private fun invalid() = AppResult.Failure(AppError(ErrorCode.PROVIDER_CHANGED, "lyrics.lrc.parse", false))

    private companion object {
        val TIME = Regex("\\[(\\d+):(\\d{2})(?:\\.(\\d{1,3}))?]")
        val METADATA = Regex("\\[(?:ar|al|ti|au|by|re|ve|length):[^]\\r\\n]*]", RegexOption.IGNORE_CASE)
    }
}
