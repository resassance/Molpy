package app.musicplayer.storage

import androidx.room.deferredTransaction
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import app.musicplayer.downloads.*
import app.musicplayer.library.CacheRetention
import app.musicplayer.library.LibraryRepository
import app.musicplayer.model.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomLibraryRepository(private val database: MusicDatabase, private val nowMs: () -> Long) :
    LibraryRepository, OfflineRepository {
    private val dao = database.dao()
    private val offline = database.offlineDao()
    private val trackRecords = TrackRecords(database)

    override suspend fun getOrCreate(source: SourceRef): TrackId = write {
        dao.identity(source.provider, source.kind, source.id)?.let {
            return@write TrackId(it.trackId)
        }
        val id = TrackId(Uuid.random().toString())
        dao.insertIdentity(
            ExternalIdentityRecord(source.provider, source.kind, source.id, id.value)
        )
        TrackId(requireNotNull(dao.identity(source.provider, source.kind, source.id)).trackId)
    }

    override suspend fun upsert(track: Track) {
        write { trackRecords.upsert(track, nowMs()) }
    }

    override suspend fun upsertAll(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        write {
            val atMs = nowMs()
            tracks.forEach { trackRecords.upsert(it, atMs) }
        }
    }

    override suspend fun getTrack(id: TrackId): Track? = read { trackRecords.load(id) }

    override suspend fun fillMissingDuration(
        id: TrackId,
        source: SourceRef,
        durationMs: Long,
    ): Track? = write {
        val current = trackRecords.load(id) ?: return@write null
        val updated = current.withMissingDuration(source, durationMs)
        if (updated != current) trackRecords.upsert(updated, nowMs())
        updated
    }

    suspend fun retainedArtworkKeys(): Set<String> = read { dao.retainedArtworkKeys().toSet() }

    override suspend fun setFavorite(id: TrackId, favorite: Boolean) {
        write {
            if (favorite) {
                if (dao.track(id.value) != null)
                    dao.insertFavorite(FavoriteRecord(id.value, nowMs()))
            } else dao.deleteFavorite(id.value)
        }
    }

    override val favorites: Flow<List<Track>> =
        dao.observeFavorites().map { records ->
            read { records.mapNotNull { trackRecords.load(TrackId(it.id)) } }
        }
    override val recent: Flow<List<Track>> =
        dao.observeRecent().map { records ->
            read { records.mapNotNull { trackRecords.load(TrackId(it.id)) } }
        }

    override suspend fun saveQueue(snapshot: QueueSnapshot) {
        write {
            require(snapshot.entries.map { it.id }.toSet().size == snapshot.entries.size) {
                "Duplicate queue entry IDs"
            }
            dao.deleteQueueEntries()
            dao.insertQueueEntries(
                snapshot.entries.mapIndexed { index, entry ->
                    QueueEntryRecord(entry.id.value, entry.trackId.value, index)
                }
            )
            dao.putQueueSnapshot(
                QueueSnapshotRecord(
                    currentEntryId = snapshot.currentEntryId?.value,
                    positionMs = snapshot.positionMs.coerceAtLeast(0),
                    playWhenReady = snapshot.playWhenReady,
                )
            )
        }
    }

    override suspend fun restoreQueue(): QueueSnapshot = read {
        val saved = dao.queueSnapshot() ?: return@read QueueSnapshot()
        val entries =
            dao.queueEntries()
                .filter { dao.track(it.trackId) != null }
                .map { QueueEntry(QueueEntryId(it.entryId), TrackId(it.trackId)) }
        if (entries.isEmpty()) return@read QueueSnapshot()
        val current = entries.firstOrNull { it.id.value == saved.currentEntryId }
        QueueSnapshot(
            entries,
            (current ?: entries.first()).id,
            if (current == null) 0 else saved.positionMs.coerceAtLeast(0),
            false,
        )
    }

    /**
     * The playback session owner calls once on the first actual start, never on state refreshes.
     */
    override suspend fun recordStarted(id: TrackId, atMs: Long) {
        write {
            if (dao.track(id.value) != null) {
                dao.putRecent(RecentRecord(id.value, atMs))
                dao.trimRecent(CacheRetention.RECENT_LIMIT)
            }
        }
    }

    override suspend fun getCachedSearch(
        query: String,
        musicOnly: Boolean,
        nowMs: Long,
    ): List<Track>? = read {
        val record = dao.cachedSearch(query, musicOnly) ?: return@read null
        if (!CacheRetention.isFresh(record.cachedAtMs, nowMs, CacheRetention.SEARCH_TTL_MS))
            return@read null
        val ids = RecordMapper.trackIds(record.trackIdsJson)
        val tracks = ids.map { trackRecords.load(it) }
        if (tracks.any { it == null }) null else tracks.filterNotNull()
    }

    override suspend fun cacheSearch(
        query: String,
        musicOnly: Boolean,
        tracks: List<Track>,
        atMs: Long,
    ) {
        write {
            val ids = tracks.map { trackRecords.upsert(it, atMs) }
            dao.putCachedSearch(
                CatalogCacheRecord(query, musicOnly, RecordMapper.trackIds(ids), atMs)
            )
        }
    }

    override suspend fun getLyrics(
        id: TrackId,
        candidateId: CandidateId,
        nowMs: Long,
    ): LyricsLookup? = read {
        val record = dao.lyrics(id.value, candidateId.value) ?: return@read null
        if (
            record.kind == "UNAVAILABLE" &&
                offline.references(id.value, candidateId.value) == 0 &&
                !CacheRetention.isFresh(
                    record.cachedAtMs,
                    nowMs,
                    CacheRetention.UNAVAILABLE_LYRICS_TTL_MS,
                )
        )
            null
        else RecordMapper.lyrics(record)
    }

    override suspend fun saveLyrics(
        id: TrackId,
        candidateId: CandidateId,
        lookup: LyricsLookup,
        atMs: Long,
    ) {
        if (lookup is LyricsLookup.Failed) return
        write {
            val record = RecordMapper.lyrics(id, candidateId, lookup, atMs)
            val old = dao.lyrics(id.value, candidateId.value)
            if (old == null || record.quality >= old.quality) dao.putLyrics(record)
        }
    }

    override suspend fun prune(nowMs: Long) {
        write {
            dao.trimRecent(CacheRetention.RECENT_LIMIT)
            dao.pruneSearch(nowMs, cutoff(nowMs, CacheRetention.SEARCH_TTL_MS))
            dao.pruneUnavailableLyrics(
                nowMs,
                cutoff(nowMs, CacheRetention.UNAVAILABLE_LYRICS_TTL_MS),
            )
            dao.unprotectedTracks().forEachIndexed { index, record ->
                if (
                    index >= CacheRetention.METADATA_LIMIT ||
                        !CacheRetention.isFresh(
                            record.cachedAtMs,
                            nowMs,
                            CacheRetention.METADATA_TTL_MS,
                        )
                ) {
                    dao.deleteTrack(record.id)
                }
            }
        }
    }

    override suspend fun getNetworkConfig(): NetworkConfig = read {
        dao.setting("network")?.let { RecordMapper.network(it.value) }
            ?: NetworkConfig(NetworkMode.SYSTEM, null, null)
    }

    override suspend fun saveNetworkConfig(config: NetworkConfig) {
        write {
            dao.putSetting(SettingsRecord("network", RecordMapper.network(config)))
        }
    }

    override suspend fun findAvailable(trackId: TrackId): OfflineAsset? = read {
        offline.available(trackId.value)?.let(OfflineRecordMapper::asset)
    }

    override suspend fun findAsset(id: AssetId): OfflineAsset? = read {
        offline.asset(id.value)?.let(OfflineRecordMapper::asset)
    }

    override suspend fun listAssets(): List<OfflineAsset> = read {
        offline.all().map(OfflineRecordMapper::asset)
    }

    override suspend fun saveMapping(asset: OfflineAsset) {
        write {
            val previous = offline.asset(asset.id.value)?.let(OfflineRecordMapper::asset)
            // Only explicit local artwork enrichment may change the saved recording snapshot.
            val updatedSnapshot =
                previous?.trackSnapshot?.let { snapshot ->
                    snapshot.copy(
                        artwork =
                            snapshot.artwork.copy(localKey = asset.trackSnapshot.artwork.localKey)
                    )
                }
            require(
                previous == null ||
                    previous.copy(
                        bytes = asset.bytes,
                        state = asset.state,
                        trackSnapshot = requireNotNull(updatedSnapshot),
                    ) == asset
            ) {
                "Existing asset identity and recording metadata cannot be reassigned"
            }
            val sameFormat =
                offline.representation(
                    asset.trackId.value,
                    asset.candidateId.value,
                    asset.representation.id,
                )
            require(sameFormat == null || sameFormat.id == asset.id.value) {
                "Representation already has an asset identity"
            }
            if (dao.track(asset.trackId.value) == null) {
                require(trackRecords.upsert(asset.trackSnapshot, nowMs()) == asset.trackId) {
                    "Offline track identity conflicts with library"
                }
            }
            offline.put(OfflineRecordMapper.record(asset))
        }
    }

    override suspend fun removeMapping(id: AssetId) {
        write { offline.remove(id.value) }
    }

    override suspend fun reconcile(existingStorageKeys: Set<String>) {
        write {
            offline
                .all()
                .filter {
                    it.state == DownloadState.AVAILABLE.name &&
                        it.storageKey !in existingStorageKeys
                }
                .forEach { offline.remove(it.id) }
        }
    }

    private fun cutoff(nowMs: Long, ttlMs: Long): Long =
        if (nowMs < Long.MIN_VALUE + ttlMs) Long.MIN_VALUE else nowMs - ttlMs

    private suspend fun <T> write(block: suspend () -> T): T =
        database.useWriterConnection { connection ->
            connection.immediateTransaction { block() }
        }

    private suspend fun <T> read(block: suspend () -> T): T =
        database.useReaderConnection { connection ->
            connection.deferredTransaction { block() }
        }
}
