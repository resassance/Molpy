package app.musicplayer.downloads

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import app.musicplayer.model.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class AdmissionResult(val stamp: ResourceStamp?, val restart: Boolean = false, val error: AppError? = null)

/** Inspects actual HTTP headers before OkHttpDataSource may skip/read a response body. */
internal object DownloadAdmission {
    fun evaluate(source: SourceRef, representation: Representation, saved: ResourceStamp?, offset: Long,
        status: Int, headers: Map<String, List<String>>, hasBytes: Boolean): AdmissionResult {
        fun denied(restart: Boolean = true) = AdmissionResult(null, restart,
            AppError(ErrorCode.INCOMPATIBLE_RESOURCE, "download.admission", false))
        try {
            fun header(name: String): String? {
                val values = headers.filterKeys { it.equals(name, true) }.values.flatten()
                if (values.isEmpty()) return null
                require(values.size == 1)
                return values.single().trim()
            }
            if (offset < 0 || status !in setOf(200, 206)) return denied(false)
            val encoding = header("Content-Encoding")
            if (encoding != null && !encoding.equals("identity", true)) return denied()
            val validator = header("ETag")?.takeIf(::strong)
            val declaredValidator = representation.validator
            if (declaredValidator != null && (!strong(declaredValidator) || validator != declaredValidator)) return denied()
            val contentLength = header("Content-Length")?.let { requireNotNull(it.toLongOrNull()).also { n -> require(n > 0) } }
            val range = header("Content-Range")
            var rangeStart: Long? = null
            val total: Long? = if (status == 206) {
                val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(range ?: "") ?: return denied()
                val start = match.groupValues[1].toLong()
                val end = match.groupValues[2].toLong()
                val size = match.groupValues[3].toLong()
                if (start > end || end >= size || size <= 0 || start != offset ||
                    contentLength?.let { it != end - start + 1 } == true) return denied()
                rangeStart = start
                size
            } else {
                if (range != null) return denied()
                contentLength
            }
            val expected = representation.expectedBytes
            if (expected?.let { it <= 0 || (total != null && it != total) } == true) return denied()
            val current = ResourceStamp(source, representation.id, validator, total ?: expected)
            if (offset == 0L && !hasBytes) return AdmissionResult(current)
            if (!hasBytes || saved == null || offset == 0L) return denied()
            val decision = ResumePolicy().decide(saved, current, offset, ResumeResponse(status, rangeStart, total, validator))
            return if (decision == ResumeDecision.RESUME) AdmissionResult(current)
                else denied(decision == ResumeDecision.RESTART)
        } catch (_: IllegalArgumentException) { return denied() }
    }

    private fun strong(value: String): Boolean = value.length >= 2 && value.first() == '"' && value.last() == '"' &&
        value.substring(1, value.lastIndex).all { it == '!' || it in '#'..'~' || it in '\u0080'..'\u00ff' }
}

/** Shared across every upstream instance/fallback/retry belonging to one owner attempt. */
internal class DownloadAttempt(val token: String) {
    @Volatile var rejection: AppError? = null
        private set
    @Synchronized fun reject(error: AppError): Boolean {
        if (rejection != null) return false
        rejection = error
        return true
    }
    fun matches(token: String): Boolean = this.token == token && rejection == null
}

/** Admission never removes bytes. Only the owner calls clean after the matching removal barrier. */
@UnstableApi
internal class DownloadRecovery(
    private val cache: Cache,
    private val rejected: (OfflineAsset, DownloadAttempt, Boolean, AppError) -> Unit,
) {
    private val attempts = ConcurrentHashMap<AssetId, DownloadAttempt>()
    fun attempt(asset: OfflineAsset): DownloadAttempt = attempts.getOrPut(asset.id) {
        DownloadAttempt(asset.id.value + "\n" + asset.representation.id + "\n" + UUID.randomUUID())
    }
    fun restore(asset: OfflineAsset, token: String) { attempts.putIfAbsent(asset.id, DownloadAttempt(token)) }
    fun current(asset: OfflineAsset, token: String): Boolean = attempts[asset.id]?.matches(token) == true
    fun reset(asset: OfflineAsset) { attempts.remove(asset.id) }

    fun reject(asset: OfflineAsset, attempt: DownloadAttempt, restart: Boolean, error: AppError) {
        if (attempt.reject(error)) rejected(asset, attempt, restart, error)
    }

    fun inspect(response: DownloadResponse, attempt: DownloadAttempt): AppResult<Unit> {
        attempt.rejection?.let { return AppResult.Failure(it) }
        if (attempts[response.asset.id] !== attempt) return AppResult.Failure(
            AppError(ErrorCode.INCOMPATIBLE_RESOURCE, "download.attempt", false))
        val result = DownloadAdmission.evaluate(response.asset.source, response.representation,
            ResourceStampMetadata.read(cache, response.asset.storageKey), response.offset, response.status,
            response.headers, cache.getCachedSpans(response.asset.storageKey).isNotEmpty())
        result.error?.let { reject(response.asset, attempt, result.restart, it); return AppResult.Failure(it) }
        // Persistence must succeed before upstream open returns and TeeDataSource opens its sink.
        return try {
            ResourceStampMetadata.write(cache, response.asset.storageKey, checkNotNull(result.stamp))
            AppResult.Success(Unit)
        } catch (failure: Exception) {
            val error = downloadError(failure, "download.stamp", ErrorCode.INCOMPLETE_MEDIA)
            reject(response.asset, attempt, false, error)
            AppResult.Failure(error)
        }
    }

    fun clean(asset: OfflineAsset) {
        cache.removeResource(asset.storageKey)
        cache.applyContentMetadataMutations(asset.storageKey, ResourceStampMetadata.removeOwned(ContentMetadataMutations())
            .remove(ContentMetadata.KEY_CONTENT_LENGTH).remove(ContentMetadata.KEY_REDIRECTED_URI))
        check(cache.getCachedSpans(asset.storageKey).isEmpty())
        check(cache.getCachedBytes(asset.storageKey, 0, Long.MAX_VALUE) == 0L)
        val metadata = cache.getContentMetadata(asset.storageKey)
        check(!metadata.contains(ContentMetadata.KEY_CONTENT_LENGTH) && !metadata.contains(ContentMetadata.KEY_REDIRECTED_URI))
        check(!ResourceStampMetadata.hasOwned(cache, asset.storageKey))
    }
}
