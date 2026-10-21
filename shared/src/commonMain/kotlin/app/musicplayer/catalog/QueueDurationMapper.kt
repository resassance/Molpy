package app.musicplayer.catalog

import app.musicplayer.model.*
import com.metrolist.innertubex.models.response.GetQueueResponse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

internal object QueueDurationMapper {
    private val json = Json { ignoreUnknownKeys = true }

    fun map(body: String, requested: List<SourceRef>): AppResult<Map<SourceRef, Long>> =
        try {
            val response = json.decodeFromString<GetQueueResponse>(body)
            val rows = response.queueDatas ?: schemaFailure()
            val sources = requested.associateBy { it.id }
            val result = mutableMapOf<SourceRef, Long>()
            for (row in rows) {
                val content = row.content ?: continue
                val video = content.objectOrNull("playlistPanelVideoRenderer")
                val music = content.objectOrNull("musicResponsiveListItemRenderer")
                val item = video ?: music ?: schemaFailure()
                val id =
                    if (video != null) item.requiredString("videoId")
                    else
                        item.objectOrNull("playlistItemData")?.stringOrNull("videoId")
                            ?: item
                                .objectOrNull("navigationEndpoint")
                                ?.objectOrNull("watchEndpoint")
                                ?.stringOrNull("videoId")
                            ?: schemaFailure()
                val source = sources[id] ?: continue
                val duration =
                    if (video != null)
                        item.objectOrNull("lengthText")?.let(::text)?.let(::durationMs)
                    else
                        item
                            .arrayOrNull("fixedColumns")
                            ?.firstOrNull()
                            ?.requiredObject()
                            ?.objectOrNull("musicResponsiveListItemFlexColumnRenderer")
                            ?.objectOrNull("text")
                            ?.let(::text)
                            ?.let(::durationMs)
                if (duration != null && duration > 0)
                    if (source !in result) result[source] = duration
            }
            AppResult.Success(result)
        } catch (_: SerializationException) {
            failure()
        } catch (_: IllegalArgumentException) {
            failure()
        } catch (_: CatalogSchemaException) {
            failure()
        }

    private fun failure() =
        AppResult.Failure(AppError(ErrorCode.PROVIDER_CHANGED, "catalog.duration.map", false))
}
