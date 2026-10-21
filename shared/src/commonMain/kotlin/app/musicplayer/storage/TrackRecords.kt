package app.musicplayer.storage

import app.musicplayer.model.*

internal class TrackRecords(private val dao: MusicDao) {
    suspend fun upsert(track: Track, atMs: Long): TrackId {
        require(track.candidates.map { it.id }.distinct().size == track.candidates.size) {
            "Duplicate candidate IDs"
        }
        val mappedIds =
            track.externalIds
                .mapNotNull { dao.identity(it.provider, it.kind, it.id)?.trackId }
                .toMutableSet()
        // Metadata may be absent before the first fetch or after pruning; durable mappings still
        // establish the ID.
        if (dao.track(track.id.value) != null || dao.identities(track.id.value).isNotEmpty())
            mappedIds += track.id.value
        // Conflicting existing identities cannot be silently reassigned and orphan a user's
        // library.
        require(mappedIds.size <= 1) { "Track joins conflicting durable identities" }
        val id = mappedIds.singleOrNull()?.let(::TrackId) ?: track.id
        dao.putTrack(RecordMapper.track(track, id, atMs))
        for (source in track.externalIds) {
            dao.insertIdentity(
                ExternalIdentityRecord(source.provider, source.kind, source.id, id.value)
            )
        }
        dao.deleteCandidates(id.value)
        dao.insertCandidates(RecordMapper.candidates(track, id))
        return id
    }

    suspend fun load(id: TrackId): Track? =
        dao.track(id.value)?.let { record ->
            RecordMapper.track(record, dao.identities(id.value), dao.candidates(id.value))
        }
}
