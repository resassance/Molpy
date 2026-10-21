package app.musicplayer.downloads

import app.musicplayer.model.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Leases are borrowed only by the live owner attempt; none of this state is durable. */
internal class DownloadLeaseCache(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Entry(
        val token: String,
        val source: SourceRef,
        val lease: ResolvedPlayback,
        val rememberedAtMs: Long,
    )

    private val active = linkedMapOf<AssetId, String>()
    private val entries = mutableMapOf<AssetId, Entry>()

    @Synchronized
    fun activate(id: AssetId, token: String) {
        if (active[id] == token) return
        active.remove(id)
        active[id] = token
        entries.remove(id)
        while (active.size > 8) {
            val oldest = active.keys.first()
            active.remove(oldest)
            entries.remove(oldest)
        }
    }

    @Synchronized
    fun remember(asset: OfflineAsset, attemptToken: String, lease: ResolvedPlayback) {
        if (active[asset.id] != attemptToken || lease.expiresAtMs == null) return
        entries[asset.id] = Entry(attemptToken, asset.source, lease, clock())
    }

    @Synchronized
    fun takeValid(
        asset: OfflineAsset,
        attemptToken: String,
        networkGeneration: Long,
        nowMs: Long,
    ): ResolvedPlayback? {
        if (active[asset.id] != attemptToken) return null
        val entry = entries[asset.id] ?: return null
        val lease = entry.lease
        if (
            entry.token != attemptToken ||
                entry.source != asset.source ||
                lease.networkGeneration != networkGeneration ||
                lease.candidateId != asset.candidateId ||
                lease.transport != TransportKind.PROGRESSIVE ||
                nowMs - entry.rememberedAtMs !in 0 until 120000 ||
                lease.expiresAtMs?.let { it <= nowMs || it - nowMs <= 30000 } != false ||
                representationCompatibility(
                    asset.source,
                    asset.representation,
                    asset.source,
                    lease.representation,
                ) != RepresentationCompatibility.COMPATIBLE
        ) {
            entries.remove(asset.id)
            return null
        }
        return lease
    }

    suspend fun resolve(
        asset: OfflineAsset,
        attemptToken: String,
        networkGeneration: Long,
        extract: suspend () -> AppResult<ResolvedPlayback>,
    ): AppResult<ResolvedPlayback> {
        if (!current(asset.id, attemptToken)) return stale()
        takeValid(asset, attemptToken, networkGeneration, clock())?.let {
            return AppResult.Success(it)
        }
        val result = extract()
        currentCoroutineContext().ensureActive()
        if (!current(asset.id, attemptToken)) return stale()
        if (result is AppResult.Success) remember(asset, attemptToken, result.value)
        return result
    }

    @Synchronized private fun current(id: AssetId, token: String) = active[id] == token

    private fun stale() =
        AppResult.Failure(AppError(ErrorCode.RESOURCE_CHANGED, "download.attempt", false))

    @Synchronized
    fun invalidate(assetId: AssetId) {
        active.remove(assetId)
        entries.remove(assetId)
    }

    @Synchronized
    fun clear() {
        active.clear()
        entries.clear()
    }
}
