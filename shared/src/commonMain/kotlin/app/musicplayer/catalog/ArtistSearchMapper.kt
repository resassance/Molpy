package app.musicplayer.catalog

import app.musicplayer.artists.*
import app.musicplayer.model.*
import kotlinx.serialization.json.*

internal object ArtistSearchMapper {
    fun map(body: String): AppResult<List<ArtistCatalogHit>> =
        try {
            val root = Json.parseToJsonElement(body).requiredObject()
            if (root["error"] != null) schemaFailure()
            val contents = root.requiredObject("contents")
            val section =
                contents.objectOrNull("sectionListRenderer")
                    ?: contents.objectOrNull("tabbedSearchResultsRenderer")?.let { tabbed ->
                        val tabs =
                            tabbed.requiredArray("tabs").map {
                                it.requiredObject().requiredObject("tabRenderer")
                            }
                        val selected =
                            tabs.firstOrNull {
                                it["selected"]?.jsonPrimitive?.booleanOrNull == true
                            } ?: tabs.firstOrNull { it["content"] is JsonObject } ?: schemaFailure()
                        selected.requiredObject("content").requiredObject("sectionListRenderer")
                    }
                    ?: schemaFailure()
            val hits = mutableListOf<ArtistCatalogHit>()
            for (value in section.requiredArray("contents")) {
                val item = value.requiredObject()
                when {
                    item["musicShelfRenderer"] != null ->
                        item
                            .requiredObject("musicShelfRenderer")
                            .requiredArray("contents")
                            .forEach {
                                row(
                                        it.requiredObject()
                                            .requiredObject("musicResponsiveListItemRenderer")
                                    )
                                    ?.let(hits::add)
                            }
                    item["musicCardShelfRenderer"] != null -> {
                        val card = item.requiredObject("musicCardShelfRenderer")
                        card(card)?.let(hits::add)
                        card.arrayOrNull("contents").orEmpty().forEach {
                            val renderer =
                                it.requiredObject().objectOrNull("musicResponsiveListItemRenderer")
                            if (renderer != null) row(renderer)?.let(hits::add)
                        }
                    }
                    item["messageRenderer"] != null ||
                        item["itemSectionRenderer"]?.let {
                            it is JsonObject &&
                                it["contents"] is JsonArray &&
                                it.requiredArray("contents").all { child ->
                                    child.requiredObject()["messageRenderer"] != null
                                }
                        } == true -> Unit
                    else -> schemaFailure()
                }
            }
            AppResult.Success(hits.distinctBy { it.source })
        } catch (_: Exception) {
            AppResult.Failure(AppError(ErrorCode.PROVIDER_CHANGED, "catalog.artist.map", false))
        }

    private fun row(row: JsonObject): ArtistCatalogHit? {
        val title =
            row.arrayOrNull("flexColumns")
                ?.firstOrNull()
                ?.requiredObject()
                ?.objectOrNull("musicResponsiveListItemFlexColumnRenderer")
                ?.objectOrNull("text")
        val navigation =
            row.objectOrNull("navigationEndpoint")
                ?: title
                    ?.arrayOrNull("runs")
                    ?.firstOrNull()
                    ?.requiredObject()
                    ?.objectOrNull("navigationEndpoint")
                ?: return null
        return hit(navigation, title, row)
    }

    private fun card(card: JsonObject): ArtistCatalogHit? {
        val title = card.objectOrNull("title")
        val navigation =
            card.objectOrNull("onTap")
                ?: title
                    ?.arrayOrNull("runs")
                    ?.firstOrNull()
                    ?.requiredObject()
                    ?.objectOrNull("navigationEndpoint")
                ?: return null
        return hit(navigation, title, card)
    }

    private fun hit(
        navigation: JsonObject,
        title: JsonObject?,
        row: JsonObject,
    ): ArtistCatalogHit? {
        if (navigation.browsePageType() != "MUSIC_PAGE_TYPE_ARTIST") return null
        val id = navigation.requiredObject("browseEndpoint").requiredString("browseId")
        val name = title?.let(::text)?.trim()?.takeIf { it.isNotEmpty() } ?: schemaFailure()
        val image =
            row.objectOrNull("thumbnail")
                ?.let(::images)
                ?.maxByOrNull {
                    (it["width"]?.jsonPrimitive?.longOrNull ?: 0) *
                        (it["height"]?.jsonPrimitive?.longOrNull ?: 0)
                }
                ?.stringOrNull("url")
        return ArtistCatalogHit(
            name,
            SourceRef("youtube", "artist", id),
            image?.let { Artwork(ArtworkKind.RELEASE, it, null) },
        )
    }

    private fun images(value: JsonElement): List<JsonObject> =
        when (value) {
            is JsonObject ->
                value.arrayOrNull("thumbnails")?.map { it.requiredObject() }
                    ?: value.values.flatMap(::images)
            is JsonArray -> value.flatMap(::images)
            else -> emptyList()
        }
}
