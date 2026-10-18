package app.musicplayer.search

/** Comparison keys only: catalog display names must not be replaced by these strings. */
class TrackNormalizer {
    fun normalizeTitle(text: String): String {
        var title = cleanDisplayTitle(text)
        title = BRACKETS.replace(title) { match ->
            val label = match.value.drop(1).dropLast(1).trim()
            if (GENERIC_VERSION.matches(label) || FEATURE.matches(label)) " " else match.value
        }
        val suffix = DELIMITED_SUFFIX.find(title)
        if (suffix != null && GENERIC_VERSION.matches(suffix.groupValues[1].trim())) {
            title = title.substring(0, suffix.range.first)
        }
        return words(TITLE_FEATURE.replace(title, ""))
    }

    fun normalizeArtist(text: String): String = words(FEATURE.replace(TOPIC.replace(text, ""), ""))

    /** Recording evidence needs an explicit annotation or a whole recognized version label. */
    fun versionMarkers(text: String): Set<String> = buildSet {
        val cleaned = cleanDisplayTitle(text)
        addAll(labelMarkers(cleaned))
        BRACKETS.findAll(cleaned).forEach { addAll(labelMarkers(it.value.drop(1).dropLast(1).trim())) }
        DELIMITED_SUFFIX.find(cleaned)?.let { addAll(labelMarkers(it.groupValues[1].trim())) }
    }

    // Free-form search intent is weaker than evidence in a recording's actual title.
    internal fun queryVersionMarkers(text: String): Set<String> = versionMarkers(text).ifEmpty {
        QUERY_SUFFIX.find(cleanDisplayTitle(text))?.let { labelMarkers(it.groupValues[1]) }.orEmpty()
    }

    internal fun normalizeQuery(text: String): String {
        val cleaned = cleanDisplayTitle(text)
        val suffix = QUERY_SUFFIX.find(cleaned)
        return normalizeTitle(if (suffix != null && GENERIC_VERSION.matches(suffix.groupValues[1]))
            cleaned.substring(0, suffix.range.first) else cleaned)
    }

    /** Strip only known presentation noise; keep featured artists and recording versions. */
    internal fun cleanDisplayTitle(text: String): String {
        var cleaned = NOISE_BRACKETS.replace(text, " ").trim()
        do {
            val previous = cleaned
            cleaned = NOISE_SUFFIX.replace(cleaned, "").trim()
        } while (cleaned != previous)
        return WHITESPACE.replace(cleaned, " ")
    }

    internal fun explicitVersionMarkers(version: String): Set<String> = labelMarkers(cleanDisplayTitle(version))

    private fun labelMarkers(label: String): Set<String> = when {
        GENERIC_VERSION.matches(label) -> VERSION_PATTERNS.filterValues { it.containsMatchIn(label) }.keys
        LIVE_DETAIL.matches(label) -> setOf("live")
        REMASTER_DETAIL.matches(label) -> setOf("remaster")
        COVER_DETAIL.matches(label) -> setOf("cover")
        else -> emptySet()
    }

    private fun words(text: String): String = buildString {
        text.lowercase().forEach { char ->
            when {
                char.code in 0x0300..0x036f -> Unit
                char in "àáâãäåāăą" -> append('a')
                char in "çćč" -> append('c')
                char in "èéêëēėęě" -> append('e')
                char in "ìíîïīı" -> append('i')
                char in "ñńň" -> append('n')
                char in "òóôõöøōő" -> append('o')
                char in "ùúûüūůű" -> append('u')
                char in "ýÿ" -> append('y')
                char == 'ß' -> append("ss")
                char.isLetterOrDigit() -> append(char)
                else -> append(' ')
            }
        }
    }.let { WHITESPACE.replace(it, " ").trim() }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val BRACKETS = Regex("\\([^()]*\\)|\\[[^\\[\\]]*\\]")
        val FEATURE = Regex("\\s*\\(?\\b(?:feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE)
        val TOPIC = Regex("\\s+[-–—]\\s+Topic\\s*$", RegexOption.IGNORE_CASE)
        const val NOISE = "(?:official\\s+(?:music\\s+)?(?:video|audio)|lyrics?|lyric\\s+video|hd|4k|1080p|720p)"
        val NOISE_BRACKETS = Regex("\\[$NOISE\\]|\\($NOISE\\)", RegexOption.IGNORE_CASE)
        val NOISE_SUFFIX = Regex("\\s+(?:[-–—|]\\s*)?$NOISE\\s*$", RegexOption.IGNORE_CASE)
        val VERSION_PATTERNS = linkedMapOf(
            "live" to "live", "remaster" to "remaster(?:ed)?", "cover" to "cover", "karaoke" to "karaoke",
            "reaction" to "reaction", "tutorial" to "tutorial", "full album" to "full[ -]+album",
            "slowed" to "slowed", "reverb" to "reverb", "acoustic" to "acoustic",
            "instrumental" to "instrumental", "remix" to "remix", "demo" to "demo",
            "nightcore" to "nightcore", "radio edit" to "radio[ -]+edit",
        ).mapValues { (_, pattern) -> Regex("\\b(?:$pattern)\\b", RegexOption.IGNORE_CASE) }
        const val GENERIC = "(?:live|remaster(?:ed)?|cover|karaoke|reaction|tutorial|full[ -]+album|" +
            "slowed(?:\\s*[+&]\\s*reverb)?|reverb|acoustic|instrumental|remix|demo|nightcore|radio[ -]+edit)"
        val GENERIC_VERSION = Regex("$GENERIC(?:\\s+version)?", RegexOption.IGNORE_CASE)
        val LIVE_DETAIL = Regex("live(?:\\s+version)?\\s+(?:at|in|from)\\s+.+", RegexOption.IGNORE_CASE)
        val REMASTER_DETAIL = Regex("(?:\\d{4}\\s+remaster(?:ed)?|remaster(?:ed)?\\s+\\d{4})(?:\\s+version)?", RegexOption.IGNORE_CASE)
        val COVER_DETAIL = Regex("cover\\s+by\\s+.+", RegexOption.IGNORE_CASE)
        val DELIMITED_SUFFIX = Regex("\\s+[-–—|:]\\s+(.+)$")
        val QUERY_SUFFIX = Regex("(?:^|\\s+)($GENERIC(?:\\s+version)?)$", RegexOption.IGNORE_CASE)
        val TITLE_FEATURE = Regex("\\b(?:feat\\.?|ft\\.?|featuring)\\s+[^()\\[\\]]*", RegexOption.IGNORE_CASE)
    }
}
