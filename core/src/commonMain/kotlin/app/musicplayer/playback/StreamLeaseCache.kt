package app.musicplayer.playback

import app.musicplayer.model.CandidateId
import app.musicplayer.model.ResolvedPlayback

/**
 * Transient, resolver-instance-scoped leases. The owner must clear/discard this cache when replacing
 * its resolver version. Access belongs to the application's serialized playback owner.
 */
class StreamLeaseCache {
    private data class Key(val candidateId: CandidateId, val representationId: String)
    private val leases = mutableMapOf<Key, ResolvedPlayback>()

    fun get(candidateId: CandidateId, representationId: String, networkGeneration: Long, nowMs: Long): ResolvedPlayback? {
        val key = Key(candidateId, representationId)
        val lease = leases[key] ?: return null
        if (lease.networkGeneration != networkGeneration || !hasLeaseLifetime(lease.expiresAtMs, nowMs)) {
            leases.remove(key)
            return null
        }
        return lease
    }

    fun put(lease: ResolvedPlayback) {
        val key = Key(lease.candidateId, lease.representation.id)
        if (lease.expiresAtMs == null) leases.remove(key) else leases[key] = lease
    }

    fun clear() = leases.clear()
}

/** Strictly more than 30 seconds, without overflowing near Long.MAX_VALUE. */
fun hasLeaseLifetime(expiresAtMs: Long?, nowMs: Long): Boolean =
    expiresAtMs != null && nowMs <= Long.MAX_VALUE - 30_000 && expiresAtMs > nowMs + 30_000
