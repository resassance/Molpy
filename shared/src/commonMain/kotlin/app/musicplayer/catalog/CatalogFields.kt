package app.musicplayer.catalog

import kotlinx.serialization.json.*

internal fun JsonElement.requiredObject() = this as? JsonObject ?: schemaFailure()

internal fun JsonObject.requiredObject(key: String) = this[key]?.requiredObject() ?: schemaFailure()

internal fun JsonObject.objectOrNull(key: String) = this[key] as? JsonObject

internal fun JsonObject.arrayOrNull(key: String) = this[key] as? JsonArray

internal fun JsonObject.requiredArray(key: String) = arrayOrNull(key) ?: schemaFailure()

internal fun JsonObject.stringOrNull(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.requiredString(key: String) =
    stringOrNull(key)?.takeIf { it.isNotBlank() } ?: schemaFailure()

internal fun JsonObject.browsePageType() =
    objectOrNull("browseEndpoint")
        ?.objectOrNull("browseEndpointContextSupportedConfigs")
        ?.objectOrNull("browseEndpointContextMusicConfig")
        ?.stringOrNull("pageType")

internal fun text(value: JsonObject): String? =
    value.stringOrNull("simpleText")
        ?: value.arrayOrNull("runs")?.joinToString("") {
            it.requiredObject().requiredString("text")
        }

internal fun durationMs(value: String): Long? {
    val parts = value.trim().split(':')
    if (parts.size !in 2..3 || parts.any { it.isEmpty() || it.any { char -> !char.isDigit() } })
        return null
    val numbers = parts.map { it.toLongOrNull() ?: return null }
    if (numbers.drop(1).any { it !in 0..59 } || numbers.first() > 999) return null
    return numbers.fold(0L) { total, number -> total * 60 + number } * 1000
}

internal fun schemaFailure(): Nothing = throw CatalogSchemaException()

internal class CatalogSchemaException : Exception()
