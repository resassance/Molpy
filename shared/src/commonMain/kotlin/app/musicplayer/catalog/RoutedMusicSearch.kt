package app.musicplayer.catalog

import app.musicplayer.diagnostics.DiagnosticEvent
import app.musicplayer.model.*
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException

class RoutedMusicSearch(
    private val active: () -> MusicSearchPort?,
    private val network: () -> NetworkContext,
    private val diagnostics: (DiagnosticEvent) -> Unit = {},
) : MusicSearchPort {
    private val filters = linkedMapOf<CatalogCursor, SearchFilter>()
    private var generation: Long? = null

    override suspend fun search(query: String, filter: SearchFilter) =
        routed(
            "catalog.search",
            "initial",
            filter = filter,
        ) {
            it.search(query, filter)
        }

    override suspend fun more(cursor: CatalogCursor) =
        routed(
            "catalog.search.more",
            "continuation",
            cursor = cursor,
        ) {
            it.more(cursor)
        }

    private suspend fun routed(
        operation: String,
        stage: String,
        filter: SearchFilter? = null,
        cursor: CatalogCursor? = null,
        action: suspend (MusicSearchPort) -> AppResult<SearchPage>,
    ): AppResult<SearchPage> {
        val started = TimeSource.Monotonic.markNow()
        val before = network()
        if (generation != before.generation) {
            filters.clear()
            generation = before.generation
        }
        val selectedFilter = filter ?: filters[cursor]
        val provider = active()
        if (!before.ready || provider == null) {
            val failure =
                AppResult.Failure(AppError(ErrorCode.NETWORK, "catalog.search.route", true))
            if (network().generation == before.generation)
                record(
                    operation,
                    stage,
                    selectedFilter,
                    failure,
                    started.elapsedNow().inWholeMilliseconds,
                )
            return failure
        }
        val result = action(provider)
        val after = network()
        if (!after.ready || after.generation != before.generation)
            throw CancellationException("Network generation changed")
        if (result is AppResult.Success && selectedFilter != null)
            result.value.next?.let {
                filters[it] = selectedFilter
                while (filters.size > 128) filters.remove(filters.keys.first())
            }
        record(operation, stage, selectedFilter, result, started.elapsedNow().inWholeMilliseconds)
        return result
    }

    private fun record(
        operation: String,
        stage: String,
        filter: SearchFilter?,
        result: AppResult<SearchPage>,
        elapsedMs: Long,
    ) {
        val page = result.valueOrNull()
        diagnostics(
            DiagnosticEvent(
                operationId = operation,
                stage = stage,
                providerVersion = "innertubex-0.10.0",
                sourceId = null,
                transport = null,
                errorCode = result.errorOrNull()?.code ?: page?.primaryFailure?.code,
                elapsedMs = elapsedMs,
                filter = filter?.name,
                itemCount = page?.items()?.size ?: 0,
                pageCount = if (page == null) 0 else 1,
            )
        )
    }
}
