package app.musicplayer.catalog

import app.musicplayer.model.*
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeHttpException
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.plugins.*
import io.ktor.client.statement.bodyAsText
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

class InnerTubeMusicSearch(
    private val session: InnerTube,
    generation: Long,
    identities: IdentityStore,
    nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : MusicSearchPort {
    private val mapper = SearchMapper(identities, nowMs)
    private val cursors = SearchCursorStore(generation)
    private val pages = linkedMapOf<CatalogCursor, SearchPage>()

    override suspend fun search(query: String, filter: SearchFilter): AppResult<SearchPage> =
        operation {
            require(query.isNotBlank())
            val mapped =
                mapper
                    .map(
                        session
                            .search(
                                YouTubeClient.WEB_REMIX,
                                query,
                                params(filter),
                                setLogin = false,
                            )
                            .bodyAsText(),
                        filter,
                    )
                    .required()
            page(mapped, SearchCursorContext(query, filter, ""))
        }

    override suspend fun more(cursor: CatalogCursor): AppResult<SearchPage> = operation {
        pages[cursor]?.let {
            return@operation it
        }
        val context = cursors.get(cursor)
        val mapped =
            mapper
                .map(
                    session
                        .search(
                            YouTubeClient.WEB_REMIX,
                            continuation = context.token,
                            setLogin = false,
                        )
                        .bodyAsText(),
                    context.filter,
                )
                .required()
        page(mapped, context.copy(seenTokens = context.seenTokens + context.token)).also {
            pages[cursor] = it
            while (pages.size > 128) pages.remove(pages.keys.first())
        }
    }

    private fun page(mapped: MappedSearchPage, context: SearchCursorContext): SearchPage {
        val seen = context.seenKeys.toMutableSet()
        val top = mapped.page.topResult?.takeIf { seen.add(it.key) }
        val sections =
            mapped.page.sections.mapNotNull { section ->
                section.copy(items = section.items.filter { seen.add(it.key) }).takeIf {
                    it.items.isNotEmpty()
                }
            }
        val empty = if (top == null && sections.isEmpty()) context.emptyPages + 1 else 0
        val next =
            mapped.continuation?.let { token ->
                if (token in context.seenTokens || empty >= 3) schemaFailure()
                cursors.put(context.copy(token = token, seenKeys = seen, emptyPages = empty))
            }
        return mapped.page.copy(topResult = top, sections = sections, next = next)
    }

    private fun params(filter: SearchFilter): String? =
        when (filter) {
            SearchFilter.ALL -> null
            SearchFilter.TRACKS -> "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
            SearchFilter.ARTISTS -> "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"
            SearchFilter.ALBUMS -> "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"
            SearchFilter.PLAYLISTS -> "Eg-KAQwIABAAGAAgACgBMABqChAEEAMQCRAFEAo%3D"
        }

    private suspend fun <T> operation(block: suspend () -> T): AppResult<T> =
        try {
            AppResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppFailureException) {
            AppResult.Failure(e.error)
        } catch (e: InnerTubeHttpException) {
            catalogHttpFailure(e.status.value, "catalog.search.http")
        } catch (e: ResponseException) {
            catalogHttpFailure(e.response.status.value, "catalog.search.http")
        } catch (_: IOException) {
            AppResult.Failure(AppError(ErrorCode.NETWORK, "catalog.search", true))
        } catch (_: HttpRequestTimeoutException) {
            AppResult.Failure(AppError(ErrorCode.NETWORK, "catalog.search", true))
        } catch (_: Exception) {
            AppResult.Failure(AppError(ErrorCode.PROVIDER_CHANGED, "catalog.search", false))
        }
}

internal fun <T> AppResult<T>.required(): T =
    when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> throw AppFailureException(error)
    }

internal fun catalogHttpFailure(status: Int, operation: String): AppResult.Failure =
    AppResult.Failure(
        AppError(
            when (status) {
                401 -> ErrorCode.LOGIN_REQUIRED
                403,
                404,
                410 -> ErrorCode.SOURCE_UNAVAILABLE
                407 -> ErrorCode.PROXY_AUTH
                429 -> ErrorCode.RATE_LIMIT
                408,
                425,
                in 500..599 -> ErrorCode.NETWORK
                else -> ErrorCode.PROVIDER_CHANGED
            },
            operation,
            status == 408 || status == 425 || status in 500..599,
        )
    )
