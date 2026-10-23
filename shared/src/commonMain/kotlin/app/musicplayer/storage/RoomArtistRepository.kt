package app.musicplayer.storage

import androidx.room.*
import app.musicplayer.artists.*
import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class RoomArtistRepository(private val database: MusicDatabase, private val nowMs: () -> Long) :
    ArtistRepository {
    private val dao = database.artistDao()
    private val store = ArtistStore(database)
    private val tracks = TrackRecords(database)

    override val directory: Flow<AppResult<ArtistDirectory>> =
        dao.observeDirectory()
            .map {
                result("artists.directory") { read { store.snapshot() } }
            }
            .distinctUntilChanged()
            .catch { error ->
                if (error is CancellationException) currentCoroutineContext().ensureActive()
                emit(AppResult.Failure(AppError(ErrorCode.DATABASE, "artists.directory", true)))
            }

    override suspend fun ensure(track: Track): AppResult<TrackPresentation> =
        write("artists.ensure") {
            require(tracks.upsert(track, nowMs()) == track.id)
            ArtistResolver.present(track, store.snapshot())
        }

    override suspend fun saveOverride(
        track: Track,
        choices: List<ArtistChoice>,
        expected: List<ArtistId>?,
    ): AppResult<ArtistWriteResult> =
        write("artists.override") {
            val before = store.snapshot()
            if (before.overrides[track.id] != expected) return@write ArtistWriteResult.STALE
            require(choices.isNotEmpty())
            val ids =
                choices
                    .map { choice ->
                        when (choice) {
                            is ArtistChoice.Existing -> ArtistResolver.root(choice.id, before)
                            is ArtistChoice.Name -> store.create(choice.name, null)
                            is ArtistChoice.Catalog -> {
                                val hit = choice.profile
                                require(
                                    hit.name.isNotBlank() &&
                                        hit.source.provider == "youtube" &&
                                        hit.source.kind == "artist"
                                )
                                store
                                    .register(
                                        ArtistAliasKey(ArtistResolver.sourceKey(hit.source)),
                                        hit.name,
                                        hit.source,
                                    )
                                    .let { ArtistResolver.root(it, store.snapshot()) }
                            }
                        }
                    }
                    .distinct()
            if (ids == expected) return@write ArtistWriteResult.APPLIED
            require(tracks.upsert(track, nowMs()) == track.id)
            dao.deleteOverride(track.id.value)
            dao.insertOverrides(
                ids.mapIndexed { i, id -> TrackArtistOverrideRecord(track.id.value, i, id.value) }
            )
            store.advanceRevision()
            store.rebuildListening()
            ArtistWriteResult.APPLIED
        }

    override suspend fun resetOverride(
        trackId: TrackId,
        expected: List<ArtistId>?,
    ): AppResult<ArtistWriteResult> =
        write("artists.reset") {
            val actual = store.snapshot().overrides[trackId]
            if (actual != expected) return@write ArtistWriteResult.STALE
            if (actual != null) {
                dao.deleteOverride(trackId.value)
                store.advanceRevision()
                store.rebuildListening()
            }
            ArtistWriteResult.APPLIED
        }

    override suspend fun previewMerge(
        origin: ArtistId,
        target: ArtistId,
        preferredSource: SourceRef?,
    ): AppResult<ArtistMergePreview> =
        result("artists.mergePreview") {
            read { mergePreview(origin, target, preferredSource, store.snapshot()) }
        }

    override suspend fun merge(preview: ArtistMergePreview): AppResult<ArtistWriteResult> =
        write("artists.merge") {
            val d = store.snapshot()
            if (
                preview.origin !in d.profiles ||
                    preview.target !in d.profiles ||
                    ArtistResolver.root(preview.origin, d) != preview.origin ||
                    ArtistResolver.root(preview.target, d) != preview.target
            )
                return@write ArtistWriteResult.STALE
            val actual = mergePreview(preview.origin, preview.target, preview.preferredSource, d)
            if (actual.scopeKey != preview.scopeKey) return@write ArtistWriteResult.STALE
            val revision = store.advanceRevision()
            dao.insertMerge(
                mergeRecord(preview.origin, preview.target, preview.preferredSource, revision)
            )
            store.rebuildListening()
            ArtistWriteResult.APPLIED
        }

    override suspend fun previewSplit(origin: ArtistId): AppResult<ArtistSplitPreview> =
        result("artists.splitPreview") { read { splitPreview(origin, store.snapshot()) } }

    override suspend fun split(preview: ArtistSplitPreview): AppResult<ArtistWriteResult> =
        write("artists.split") {
            val d = store.snapshot()
            if (preview.origin !in d.merges) return@write ArtistWriteResult.STALE
            if (splitPreview(preview.origin, d).scopeKey != preview.scopeKey)
                return@write ArtistWriteResult.STALE
            dao.deleteMerge(preview.origin.value)
            store.advanceRevision()
            store.rebuildListening()
            ArtistWriteResult.APPLIED
        }

    private suspend fun mergePreview(
        origin: ArtistId,
        target: ArtistId,
        preferred: SourceRef?,
        d: ArtistDirectory,
    ): ArtistMergePreview {
        val a = ArtistResolver.root(origin, d)
        val b = ArtistResolver.root(target, d)
        require(a != b)
        val group = members(setOf(a, b), d)
        require(preferred == null || group.any { d.profiles.getValue(it).source == preferred })
        val combined =
            d.copy(merges = d.merges + (a to ArtistMerge(a, b, preferred, d.revision + 1)))
        val time =
            ArtistListeningProjection.aggregate(store.sessions(), combined)
                .firstOrNull { it.key == b.value }
                ?.playedMs ?: 0
        return ArtistMergePreview(
            a,
            b,
            scope(group, d),
            savedCount(group, d),
            time,
            preferred,
        )
    }

    private suspend fun splitPreview(origin: ArtistId, d: ArtistDirectory): ArtistSplitPreview {
        require(origin in d.merges)
        val root = ArtistResolver.root(origin, d)
        val group = members(setOf(root), d)
        val split = d.copy(merges = d.merges - origin)
        val detached = members(setOf(origin), split)
        return ArtistSplitPreview(
            origin,
            scope(group, d),
            detached.sortedBy { it.value }.map { d.profiles.getValue(it) },
            savedCount(detached, split),
        )
    }

    private fun members(roots: Set<ArtistId>, d: ArtistDirectory) =
        d.profiles.keys.filter { ArtistResolver.root(it, d) in roots }.toSet()

    private suspend fun savedCount(group: Set<ArtistId>, d: ArtistDirectory): Int {
        val saved = database.collectionDao().collectionTrackIds().toSet()
        return affectedTracks(group, d).count { it.first.value in saved }
    }

    private suspend fun affectedTracks(
        group: Set<ArtistId>,
        d: ArtistDirectory,
    ): List<Pair<TrackId, List<ArtistId>>> =
        database.dao().allTracks().mapNotNull { record ->
            val track = requireNotNull(tracks.load(TrackId(record.id)))
            val ids =
                d.overrides[track.id]
                    ?: track.artists.indices.mapNotNull {
                        d.aliases[ArtistResolver.alias(track, it)]
                    }
            if (ids.any { it in group }) track.id to ids else null
        }

    // Structural scope intentionally excludes checkpoint time and global revision.
    private suspend fun scope(group: Set<ArtistId>, d: ArtistDirectory): String = buildString {
        fun part(s: String) {
            append(s.length).append(':').append(s)
        }
        for (id in group.sortedBy { it.value }) {
            val p = d.profiles.getValue(id)
            part(id.value)
            part(p.name)
            part(p.source?.let(ArtistResolver::sourceKey).orEmpty())
            val edge = d.merges[id]
            part(edge?.target?.value.orEmpty())
            part(edge?.preferredSource?.let(ArtistResolver::sourceKey).orEmpty())
        }
        for ((key, id) in
            d.aliases.entries.filter { it.value in group }.sortedBy { it.key.value }) {
            part(key.value)
            part(id.value)
        }
        val saved = database.collectionDao().collectionTrackIds().toSet()
        for ((track, ids) in affectedTracks(group, d)) {
            part(if (track.value in saved) "saved" else "cached")
            part(track.value)
            part(if (track in d.overrides) "override" else "raw")
            part(ids.size.toString())
            ids.forEach { part(it.value) }
        }
    }

    private fun mergeRecord(
        origin: ArtistId,
        target: ArtistId,
        source: SourceRef?,
        revision: Long,
    ) =
        ArtistMergeRecord(
            origin.value,
            target.value,
            source?.provider,
            source?.kind,
            source?.id,
            revision,
        )

    private suspend fun <T> read(block: suspend () -> T): T = database.useReaderConnection {
        it.deferredTransaction { block() }
    }

    private suspend fun <T> write(operation: String, block: suspend () -> T): AppResult<T> =
        result(operation) {
            val callerContext = currentCoroutineContext()
            database.useWriterConnection {
                it.immediateTransaction {
                    callerContext.ensureActive()
                    block().also { callerContext.ensureActive() }
                }
            }
        }

    private suspend fun <T> result(operation: String, block: suspend () -> T): AppResult<T> =
        try {
            AppResult.Success(block())
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            AppResult.Failure(AppError(ErrorCode.DATABASE, operation, true))
        } catch (_: IllegalArgumentException) {
            AppResult.Failure(AppError(ErrorCode.INVALID_CONFIG, operation, false))
        } catch (_: Exception) {
            AppResult.Failure(AppError(ErrorCode.DATABASE, operation, true))
        }
}
