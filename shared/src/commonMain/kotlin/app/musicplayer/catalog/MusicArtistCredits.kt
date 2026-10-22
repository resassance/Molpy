package app.musicplayer.catalog

import app.musicplayer.model.*
import kotlinx.serialization.json.*

internal fun musicArtistCredits(raw: JsonObject, context: BrowseTrackContext): List<ArtistRef> {
    val credits = mutableListOf<ArtistRef>()
    val columns = raw.requiredArray("flexColumns").drop(1)
    for ((index, column) in columns.withIndex()) {
        val runs =
            column
                .requiredObject()
                .requiredObject("musicResponsiveListItemFlexColumnRenderer")
                .objectOrNull("text")
                ?.arrayOrNull("runs")
                .orEmpty()
                .map { it.requiredObject() }
        val first = runs.takeWhile { it.stringOrNull("text")?.trim() !in creditSections }
        val plainAllowed =
            index == 0 &&
                first.none {
                    val endpoint = it.objectOrNull("navigationEndpoint")
                    endpoint != null && endpoint.browsePageType() != "MUSIC_PAGE_TYPE_ARTIST"
                }
        var inFirst = true
        val pending = StringBuilder()
        fun flush() {
            val name = pending.toString().trim()
            if (name.isNotEmpty() && durationMs(name) == null && name.lowercase() !in resultLabels)
                credits += ArtistRef(name, null)
            pending.clear()
        }
        for (run in runs) {
            val name = run.stringOrNull("text").orEmpty()
            val endpoint = run.objectOrNull("navigationEndpoint")
            when {
                name.trim() in creditSections -> {
                    flush()
                    inFirst = false
                }
                endpoint?.browsePageType() == "MUSIC_PAGE_TYPE_ARTIST" -> {
                    flush()
                    credits +=
                        ArtistRef(
                            run.requiredString("text"),
                            SourceRef(
                                "youtube",
                                "artist",
                                endpoint.requiredObject("browseEndpoint").requiredString("browseId"),
                            ),
                        )
                }
                name.trim() in creditSeparators -> flush()
                plainAllowed && inFirst && endpoint == null -> pending.append(name)
            }
        }
        flush()
    }
    return credits.distinct().ifEmpty { context.artists }
}

private val creditSections = setOf("•", "·")
private val creditSeparators = setOf(",", "&", "、")
private val resultLabels =
    setOf("song", "track", "video", "single", "песня", "трек", "видео", "сингл")
