package app.musicplayer.catalog

import app.musicplayer.model.*
import kotlinx.serialization.json.*

internal fun catalogSections(root: JsonObject): List<JsonObject> {
    val contents = root.objectOrNull("contents") ?: schemaFailure()
    contents.objectOrNull("sectionListRenderer")?.let {
        return it.requiredArray("contents").map { item -> item.requiredObject() }
    }
    val tabbed = contents.objectOrNull("tabbedSearchResultsRenderer") ?: schemaFailure()
    val tabs =
        tabbed.requiredArray("tabs").map { it.requiredObject().requiredObject("tabRenderer") }
    val tab =
        tabs.firstOrNull { it["selected"]?.jsonPrimitive?.booleanOrNull == true }
            ?: tabs.firstOrNull { it["content"] is JsonObject }
            ?: schemaFailure()
    return tab.requiredObject("content")
        .requiredObject("sectionListRenderer")
        .requiredArray("contents")
        .map { it.requiredObject() }
}

internal fun catalogImages(value: JsonElement): List<JsonObject> =
    when (value) {
        is JsonObject ->
            value.arrayOrNull("thumbnails")?.map { it.requiredObject() }
                ?: value.values.flatMap(::catalogImages)
        is JsonArray -> value.flatMap(::catalogImages)
        else -> emptyList()
    }

internal fun catalogArtwork(value: JsonObject): Artwork? =
    catalogImages(value)
        .maxByOrNull {
            (it["width"]?.jsonPrimitive?.longOrNull ?: 0) *
                (it["height"]?.jsonPrimitive?.longOrNull ?: 0)
        }
        ?.stringOrNull("url")
        ?.takeIf { it.isNotBlank() }
        ?.let { Artwork(ArtworkKind.RELEASE, it, null) }

internal fun catalogContinuation(value: JsonObject): String? {
    value.arrayOrNull("continuations")?.forEach { raw ->
        val data = raw.requiredObject()
        (data.objectOrNull("nextContinuationData") ?: data.objectOrNull("reloadContinuationData"))
            ?.stringOrNull("continuation")
            ?.let {
                return it
            }
    }
    return (value.arrayOrNull("contents") ?: value.arrayOrNull("continuationItems"))
        .orEmpty()
        .firstNotNullOfOrNull {
            it.requiredObject()
                .objectOrNull("continuationItemRenderer")
                ?.objectOrNull("continuationEndpoint")
                ?.objectOrNull("continuationCommand")
                ?.stringOrNull("token")
        }
}

internal fun isPublicPlaylistId(id: String): Boolean =
    id.matches(Regex("[A-Za-z0-9_-]+")) &&
        (id.startsWith("PL") || id.startsWith("OLAK") || id.startsWith("RDCLAK"))
