package app.musicplayer.storage

import androidx.room.*
import app.musicplayer.artists.*
import app.musicplayer.collections.CollectionGrouping
import app.musicplayer.listening.*
import app.musicplayer.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RoomListeningRepository(private val database: MusicDatabase, private val nowMs: () -> Long) :
    ListeningRepository {
    private val dao = database.listeningDao()
    private val tracks = TrackRecords(database)
    private val json = Json
    private val artistStore = ArtistStore(database)

    override val profile: Flow<AppResult<ListeningProfile>> =
        dao.observeChanges()
            .map {
                result("listening.profile") {
                    read {
                        val rankedTracks =
                            dao.tracks().map { record ->
                                TrackListening(
                                    requireNotNull(tracks.load(TrackId(record.trackId))),
                                    record.playedMs,
                                    record.lastPlayedAtMs,
                                )
                            }
                        val directory = artistStore.snapshot()
                        val rankedArtists =
                            dao.artists().map { record ->
                                val resolved =
                                    ArtistResolver.profile(ArtistId(record.key), directory)
                                ArtistListening(
                                    record.key,
                                    ArtistRef(resolved.name, resolved.source),
                                    record.playedMs,
                                    record.lastPlayedAtMs,
                                )
                            }
                        ListeningProfile(
                            rankedTracks.fold(0L) { sum, track -> addTime(sum, track.playedMs) },
                            rankedTracks,
                            rankedArtists,
                            directory.revision,
                        )
                    }
                }
            }
            .catch { error ->
                if (error is CancellationException) currentCoroutineContext().ensureActive()
                emit(AppResult.Failure(AppError(ErrorCode.DATABASE, "listening.profile", true)))
            }

    override suspend fun save(checkpoint: ListeningCheckpoint): AppResult<Unit> =
        result("listening.save") {
            val callerContext = currentCoroutineContext()
            database.useWriterConnection { connection ->
                connection.immediateTransaction {
                    callerContext.ensureActive()
                    val c = checkpoint
                    require(
                        c.sessionId.isNotBlank() &&
                            !c.sessionId.startsWith("legacy/") &&
                            c.revision > 0 &&
                            c.playedMs >= 0
                    )
                    require(c.lastPlayedAtMs >= c.startedAtMs)
                    val old = dao.session(c.sessionId)
                    if (old != null) {
                        require(
                            old.trackId == c.track.id.value &&
                                old.entryId == c.entryId.value &&
                                old.startedAtMs == c.startedAtMs
                        )
                        if (c.revision < old.revision) return@immediateTransaction
                        if (c.revision == old.revision) {
                            require(
                                c.playedMs == old.playedMs && c.lastPlayedAtMs == old.lastPlayedAtMs
                            )
                            return@immediateTransaction
                        }
                        require(
                            c.playedMs >= requireNotNull(old.playedMs) &&
                                c.lastPlayedAtMs >= old.lastPlayedAtMs
                        )
                    }
                    val canonical = tracks.upsert(c.track, nowMs())
                    require(canonical == c.track.id)
                    val credits =
                        old?.creditsJson?.let { json.decodeFromString<List<ArtistCredit>>(it) }
                            ?: credits(c.track)
                    val delta = c.playedMs - (old?.playedMs ?: 0)
                    dao.putSession(
                        ListeningSessionRecord(
                            c.sessionId,
                            c.revision,
                            c.entryId.value,
                            canonical.value,
                            c.startedAtMs,
                            c.lastPlayedAtMs,
                            c.playedMs,
                            json.encodeToString(credits),
                        )
                    )
                    val priorTrack = dao.track(canonical.value)
                    dao.putTrack(
                        TrackListeningRecord(
                            canonical.value,
                            addTime(priorTrack?.playedMs ?: 0, delta),
                            maxOf(priorTrack?.lastPlayedAtMs ?: Long.MIN_VALUE, c.lastPlayedAtMs),
                        )
                    )
                    val artistDao = database.artistDao()
                    if (old == null) {
                        val directory = artistStore.snapshot()
                        val originalIds =
                            c.track.artists.indices
                                .mapNotNull { directory.aliases[ArtistResolver.alias(c.track, it)] }
                                .distinct()
                        artistDao.insertCredits(
                            originalIds.map { SessionArtistRecord(c.sessionId, it.value) }
                        )
                    }
                    val directory = artistStore.snapshot()
                    val originalIds =
                        artistDao.sessionCredits(c.sessionId).map { ArtistId(it.artistId) }
                    val effective =
                        (directory.overrides[canonical] ?: originalIds)
                            .map { ArtistResolver.root(it, directory) }
                            .distinct()
                    for (id in effective) {
                        val profile = ArtistResolver.profile(id, directory)
                        val prior = dao.artist(id.value)
                        dao.putArtist(
                            ArtistListeningRecord(
                                id.value,
                                profile.name,
                                profile.source?.provider,
                                profile.source?.kind,
                                profile.source?.id,
                                addTime(prior?.playedMs ?: 0, delta),
                                maxOf(prior?.lastPlayedAtMs ?: Long.MIN_VALUE, c.lastPlayedAtMs),
                            )
                        )
                    }
                    callerContext.ensureActive()
                }
            }
        }

    override suspend fun history(cursor: HistoryCursor?, limit: Int): AppResult<HistoryPage> =
        result("listening.history") {
            require(limit in 1..100)
            read {
                val rows =
                    dao.history(
                        cursor != null,
                        cursor?.startedAtMs ?: 0,
                        cursor?.id.orEmpty(),
                        limit + 1,
                    )
                val visible = rows.take(limit)
                val items = visible.map { record ->
                    HistoryItem(
                        record.id,
                        requireNotNull(tracks.load(TrackId(record.trackId))),
                        record.startedAtMs,
                        record.playedMs,
                    )
                }
                HistoryPage(
                    items,
                    visible
                        .lastOrNull()
                        ?.takeIf { rows.size > limit }
                        ?.let { HistoryCursor(it.startedAtMs, it.id) },
                )
            }
        }

    private fun credits(track: Track): List<ArtistCredit> =
        track.artists
            .mapIndexed { index, artist ->
                ArtistCredit(
                    CollectionGrouping.artistKey(track, index),
                    artist.name,
                    artist.source?.provider,
                    artist.source?.kind,
                    artist.source?.id,
                )
            }
            .distinctBy { it.key }

    private fun addTime(a: Long, b: Long): Long {
        require(a >= 0 && b >= 0 && a <= Long.MAX_VALUE - b)
        return a + b
    }

    private suspend fun <T> read(block: suspend () -> T): T = database.useReaderConnection { c ->
        c.deferredTransaction { block() }
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

    @Serializable
    private data class ArtistCredit(
        val key: String,
        val name: String,
        val provider: String?,
        val kind: String?,
        val sourceId: String?,
    )
}
