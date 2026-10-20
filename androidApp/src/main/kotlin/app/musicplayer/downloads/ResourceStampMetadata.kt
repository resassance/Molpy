package app.musicplayer.downloads

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadataMutations
import app.musicplayer.model.SourceRef

/** Stable identity only. Admission writes this atomically before bytes, under owner/key exclusion. */
@UnstableApi
internal object ResourceStampMetadata {
    private const val VERSION = "custom_musicplayer_stamp_version"
    private const val PROVIDER = "custom_musicplayer_source_provider"
    private const val KIND = "custom_musicplayer_source_kind"
    private const val SOURCE_ID = "custom_musicplayer_source_id"
    private const val REPRESENTATION = "custom_musicplayer_representation_id"
    private const val VALIDATOR = "custom_musicplayer_strong_validator"
    private const val EXPECTED_BYTES = "custom_musicplayer_expected_bytes"
    private val keys = listOf(VERSION, PROVIDER, KIND, SOURCE_ID, REPRESENTATION, VALIDATOR, EXPECTED_BYTES)

    fun read(cache: Cache, key: String): ResourceStamp? {
        val metadata = cache.getContentMetadata(key)
        if (metadata.get(VERSION, -1L) != 1L) return null
        val provider = metadata.get(PROVIDER, "") ?: return null
        val kind = metadata.get(KIND, "") ?: return null
        val sourceId = metadata.get(SOURCE_ID, "") ?: return null
        val representation = metadata.get(REPRESENTATION, "") ?: return null
        if (provider.isBlank() || kind.isBlank() || sourceId.isBlank() || representation.isBlank()) return null
        return ResourceStamp(SourceRef(provider, kind, sourceId), representation,
            if (metadata.contains(VALIDATOR)) metadata.get(VALIDATOR, "") else null,
            if (metadata.contains(EXPECTED_BYTES)) metadata.get(EXPECTED_BYTES, -1L) else null)
    }

    fun write(cache: Cache, key: String, stamp: ResourceStamp) {
        val changes = removal().set(VERSION, 1L)
            .set(PROVIDER, stamp.source.provider).set(KIND, stamp.source.kind).set(SOURCE_ID, stamp.source.id)
            .set(REPRESENTATION, stamp.representationId)
        stamp.strongValidator?.let { changes.set(VALIDATOR, it) }
        stamp.expectedBytes?.let { changes.set(EXPECTED_BYTES, it) }
        cache.applyContentMetadataMutations(key, changes)
    }

    /** Recovery combines this with removal of Media3 length/redirect metadata in ONE mutation. */
    fun removeOwned(changes: ContentMetadataMutations): ContentMetadataMutations =
        changes.apply { keys.forEach { remove(it) } }

    fun hasOwned(cache: Cache, key: String): Boolean =
        cache.getContentMetadata(key).let { metadata -> keys.any { metadata.contains(it) } }

    private fun removal() = removeOwned(ContentMetadataMutations())
}
