package app.musicplayer.storage

import androidx.room.*
import app.musicplayer.artists.*
import app.musicplayer.diagnostics.DiagnosticEvent
import app.musicplayer.model.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class RoomArtistArtworkRepository(
    private val database: MusicDatabase,
    private val diagnostics: (DiagnosticEvent) -> Unit = {},
) : ArtistArtworkRepository {
    private val dao = database.artistArtworkDao()

    override fun observe(source: SourceRef): Flow<ArtistArtworkEntry?> {
        if (!valid(source)) return flowOf(null)
        return dao.observe(ArtistResolver.sourceKey(source))
            .map { decode(it, source) }
            .distinctUntilChanged()
            .catch { error ->
                if (error is CancellationException) throw error
                diagnostic("read", ErrorCode.DATABASE)
                emit(null)
            }
    }

    override suspend fun get(source: SourceRef): AppResult<ArtistArtworkEntry?> =
        result("get") {
            require(valid(source))
            decode(dao.get(ArtistResolver.sourceKey(source)), source)
        }

    override suspend fun save(entry: ArtistArtworkEntry): AppResult<Unit> =
        result("save") {
            require(valid(entry.source))
            require(entry.checkedAtMs >= 0 && entry.refreshAfterMs >= entry.checkedAtMs)
            val normalized = entry.artwork?.let(::normalize)
            require(entry.outcome != ArtistArtworkOutcome.AVAILABLE || normalized != null)
            require(entry.outcome != ArtistArtworkOutcome.ABSENT || normalized == null)
            val key = ArtistResolver.sourceKey(entry.source)
            val caller = currentCoroutineContext()
            database.useWriterConnection { connection ->
                connection.immediateTransaction {
                    caller.ensureActive()
                    val existing = dao.get(key)
                    if (existing != null && existing.checkedAtMs > entry.checkedAtMs)
                        return@immediateTransaction
                    val artwork =
                        if (entry.outcome == ArtistArtworkOutcome.RETRY) {
                            decode(existing, entry.source)?.artwork ?: normalized
                        } else normalized
                    dao.put(
                        ArtistArtworkRecord(
                            key,
                            entry.source.provider,
                            entry.source.kind,
                            entry.source.id,
                            artwork?.let {
                                Json.encodeToString(Photo(it.kind.name, it.remoteUrl, null))
                            },
                            entry.checkedAtMs,
                            entry.refreshAfterMs,
                            entry.outcome.name,
                        )
                    )
                    dao.trim()
                    caller.ensureActive()
                }
            }
        }

    private fun decode(record: ArtistArtworkRecord?, source: SourceRef): ArtistArtworkEntry? {
        if (record == null) return null
        return try {
            require(valid(source))
            require(record.sourceKey == ArtistResolver.sourceKey(source))
            require(SourceRef(record.provider, record.kind, record.sourceId) == source)
            require(record.checkedAtMs >= 0 && record.refreshAfterMs >= record.checkedAtMs)
            val outcome = ArtistArtworkOutcome.valueOf(record.outcome)
            val artwork =
                record.artworkJson?.let {
                    val photo = Json.decodeFromString<Photo>(it)
                    require(photo.localKey == null)
                    normalize(Artwork(ArtworkKind.valueOf(photo.kind), photo.remoteUrl, null))
                }
            require(outcome != ArtistArtworkOutcome.AVAILABLE || artwork != null)
            require(outcome != ArtistArtworkOutcome.ABSENT || artwork == null)
            ArtistArtworkEntry(source, artwork, record.checkedAtMs, record.refreshAfterMs, outcome)
        } catch (_: Exception) {
            diagnostic("decode", ErrorCode.INVALID_CONFIG)
            null
        }
    }

    private fun normalize(artwork: Artwork): Artwork {
        require(artwork.kind != ArtworkKind.PLACEHOLDER)
        val address = requireNotNull(artwork.remoteUrl).trim()
        require(address.isNotEmpty())
        val url = Url(address)
        require(url.protocol == URLProtocol.HTTP || url.protocol == URLProtocol.HTTPS)
        require(url.host.isNotBlank() && url.user.isNullOrEmpty() && url.password.isNullOrEmpty())
        return artwork.copy(remoteUrl = address, localKey = null)
    }

    private fun valid(source: SourceRef) = source.provider == "youtube" && source.kind == "artist"

    private fun diagnostic(stage: String, code: ErrorCode) {
        try {
            diagnostics(DiagnosticEvent("artistArtwork", stage, null, null, null, code, 0))
        } catch (_: Exception) {}
    }

    private suspend fun <T> result(operation: String, block: suspend () -> T): AppResult<T> =
        try {
            AppResult.Success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (_: IllegalArgumentException) {
            AppResult.Failure(AppError(ErrorCode.INVALID_CONFIG, "artistArtwork.$operation", false))
        } catch (_: Exception) {
            diagnostic(operation, ErrorCode.DATABASE)
            AppResult.Failure(AppError(ErrorCode.DATABASE, "artistArtwork.$operation", true))
        }

    @Serializable
    private data class Photo(val kind: String, val remoteUrl: String?, val localKey: String?)
}
