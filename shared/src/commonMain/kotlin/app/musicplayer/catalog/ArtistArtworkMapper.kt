package app.musicplayer.catalog

import app.musicplayer.model.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

internal object ArtistArtworkMapper {
    fun map(body: String, source: SourceRef): AppResult<Artwork?> {
        if (!validArtistArtworkSource(source))
            return AppResult.Failure(
                AppError(ErrorCode.SOURCE_UNAVAILABLE, "catalog.artist.artwork.source", false)
            )
        return try {
            val root = Json.parseToJsonElement(body).requiredObject()
            if (root["error"] != null) schemaFailure()
            AppResult.Success(photo(musicArtistHeader(root)))
        } catch (_: SerializationException) {
            changed()
        } catch (_: IllegalArgumentException) {
            changed()
        } catch (_: CatalogSchemaException) {
            changed()
        }
    }

    internal fun musicArtistHeader(root: JsonObject): JsonObject {
        val header =
            root.objectOrNull("header")?.objectOrNull("musicImmersiveHeaderRenderer")
                ?: schemaFailure()
        if (header.objectOrNull("title")?.let(::text).isNullOrBlank()) schemaFailure()
        return header
    }

    internal fun photo(header: JsonObject): Artwork? {
        fun thumbnails(value: JsonElement): List<JsonObject> =
            when (value) {
                is JsonObject ->
                    value.arrayOrNull("thumbnails")?.map { it.requiredObject() }
                        ?: value.values.flatMap(::thumbnails)
                is JsonArray -> value.flatMap(::thumbnails)
                else -> emptyList()
            }
        val image =
            listOfNotNull(
                    header.objectOrNull("thumbnail"),
                    header.objectOrNull("thumbnailRenderer"),
                    header.objectOrNull("foregroundThumbnail"),
                )
                .flatMap(::thumbnails)
                .maxByOrNull {
                    (it["width"]?.jsonPrimitive?.longOrNull ?: 0) *
                        (it["height"]?.jsonPrimitive?.longOrNull ?: 0)
                }
        return image
            ?.stringOrNull("url")
            ?.takeIf { it.isNotBlank() }
            ?.let { Artwork(ArtworkKind.RELEASE, it, null) }
    }

    private fun changed() =
        AppResult.Failure(AppError(ErrorCode.PROVIDER_CHANGED, "catalog.artist.artwork.map", false))
}

internal fun validArtistArtworkSource(source: SourceRef): Boolean =
    source.provider == "youtube" && source.kind == "artist" && source.id.isNotBlank()
