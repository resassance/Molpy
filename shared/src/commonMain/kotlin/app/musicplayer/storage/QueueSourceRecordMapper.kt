package app.musicplayer.storage

import app.musicplayer.catalog.*
import app.musicplayer.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class RestoredQueueSource(val source: QueueSourceState?, val error: AppError?)

internal object QueueSourceRecordMapper {
    @Serializable
    private data class Ref(val provider: String, val kind: String, val id: String) {
        fun domain() = SourceRef(provider, kind, id)
    }

    @Serializable private data class Row(val ordinal: Int, val itemId: String?, val source: Ref?)

    @Serializable
    private data class Payload(
        val version: Int,
        val unavailable: Boolean = false,
        val sessionId: String? = null,
        val title: String? = null,
        val source: Ref? = null,
        val selectedOrdinal: Int = 0,
        val rows: List<Row> = emptyList(),
        val deferredEndIds: List<String> = emptyList(),
        val phase: String = "LOADING",
        val waiting: Boolean = false,
        val shuffleRequested: Boolean = false,
        val errorCode: String? = null,
    )

    private fun SourceRef.record() = Ref(provider, kind, id)

    fun encode(source: QueueSourceState?, recoveryError: AppError?): String? {
        if (source == null)
            return if (recoveryError == null) null
            else Json.encodeToString(Payload(version = 1, unavailable = true))
        return Json.encodeToString(
            Payload(
                version = 1,
                sessionId = source.sessionId,
                title = source.ref.title,
                source = source.ref.source.record(),
                selectedOrdinal = source.selectedOrdinal,
                rows =
                    source.acceptedRows.map {
                        Row(it.ordinal, it.itemId, it.playbackSource?.record())
                    },
                deferredEndIds = source.deferredEndIds.map { it.value },
                phase = source.phase.name,
                waiting = source.waiting,
                shuffleRequested = source.shuffleRequested,
                errorCode = source.error?.code?.name,
            )
        )
    }

    fun decode(raw: String?): RestoredQueueSource {
        if (raw == null) return RestoredQueueSource(null, null)
        return try {
            val payload = Json.decodeFromString<Payload>(raw)
            require(payload.version == 1 && !payload.unavailable)
            val ref = requireNotNull(payload.source).domain()
            require(
                ref.provider in setOf("youtube", "youtube_music") &&
                    ref.kind == "playlist" &&
                    ref.id.isNotBlank()
            )
            val sessionId = requireNotNull(payload.sessionId).also { require(it.isNotBlank()) }
            val rows =
                payload.rows.mapIndexed { index, row ->
                    require(row.ordinal == index)
                    PlaylistRowIdentity(index, row.itemId, row.source?.domain())
                }
            require(payload.selectedOrdinal in rows.indices)
            val phase = QueueSourcePhase.valueOf(payload.phase)
            val ids = payload.deferredEndIds.map(::QueueEntryId)
            require(ids.distinct().size == ids.size)
            val error =
                payload.errorCode?.let {
                    AppError(
                        ErrorCode.valueOf(it),
                        "queue.source.restore",
                        phase == QueueSourcePhase.FAILED,
                    )
                }
            RestoredQueueSource(
                QueueSourceState(
                    sessionId,
                    PublicPlaylistRef(requireNotNull(payload.title), ref),
                    payload.selectedOrdinal,
                    rows,
                    ids,
                    phase,
                    payload.waiting,
                    payload.shuffleRequested,
                    error,
                ),
                null,
            )
        } catch (_: IllegalArgumentException) {
            unavailable()
        } catch (_: IllegalStateException) {
            unavailable()
        }
    }

    private fun unavailable() =
        RestoredQueueSource(
            null,
            AppError(ErrorCode.PROVIDER_CHANGED, "queue.source.restore", false),
        )
}
