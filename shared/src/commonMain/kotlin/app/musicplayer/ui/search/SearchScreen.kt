package app.musicplayer.ui.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.catalog.*
import app.musicplayer.model.*
import app.musicplayer.ui.components.*

@Composable
fun SearchScreen(
    model: SearchModel,
    artwork: ArtworkLoader,
    current: TrackId?,
    onMenu: (Track) -> Unit,
    onArtist: (ArtistRef) -> Unit = {},
    onAlbum: (AlbumRef) -> Unit = {},
    onPublicPlaylist: (PublicPlaylistRef) -> Unit = {},
) {
    val state by model.state.collectAsState()
    var query by rememberSaveable { mutableStateOf(model.query.value) }
    val keyboard = LocalSoftwareKeyboardController.current
    val list =
        remember(state.query, state.filter) {
            LazyListState(state.scroll.index, state.scroll.offset)
        }
    fun saveScroll() =
        model.updateScroll(
            list.firstVisibleItemIndex,
            list.firstVisibleItemScrollOffset,
            state.query,
            state.filter,
        )
    LaunchedEffect(list, state.query, state.filter) {
        val listQuery = state.query
        val listFilter = state.filter
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                model.updateScroll(index, offset, listQuery, listFilter)
            }
    }
    LaunchedEffect(list, state.page?.next) {
        val listQuery = state.query
        val listFilter = state.filter
        snapshotFlow {
            list.layoutInfo.visibleItemsInfo.lastOrNull()?.index to list.layoutInfo.totalItemsCount
        }
            .collect { (last, count) ->
                if (
                    model.state.value.query == listQuery &&
                        model.state.value.filter == listFilter &&
                        last != null &&
                        count > 0 &&
                        last >= count - 3
                )
                    model.more()
            }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Text("Поиск", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                placeholder = { Text("Название или исполнитель") },
                leadingIcon = { Icon(AppIcons.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty())
                        IconButton({
                            query = ""
                            model.search("")
                        }) {
                            Icon(AppIcons.Close, "Очистить поиск")
                        }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions =
                    KeyboardActions(
                        onSearch = {
                            keyboard?.hide()
                            model.search(query)
                        }
                    ),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = {
                            saveScroll()
                            model.selectFilter(filter)
                        },
                        label = { Text(filter.label()) },
                    )
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            if (state.loading)
                item(key = "search-loading") {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
            state.error?.let { error ->
                item(key = "search-error") { InlineError(error) { model.retry() } }
            }
            val page = state.page
            page?.topResult?.let { top ->
                item(key = "search-top-heading") { SearchHeading("Главный результат") }
                item(key = top.key) {
                    SearchResultRow(
                        top,
                        artwork,
                        current,
                        model::play,
                        onMenu,
                        {
                            saveScroll()
                            onArtist(it)
                        },
                        {
                            saveScroll()
                            onAlbum(it)
                        },
                        {
                            saveScroll()
                            onPublicPlaylist(it)
                        },
                    )
                }
            }
            val shown = mutableSetOf<String>()
            page?.topResult?.let { shown += it.key }
            page?.sections.orEmpty().forEach { section ->
                val unique = section.items.filter { shown.add(it.key) }
                if (unique.isNotEmpty()) {
                    item(key = "section:${section.filter}:${unique.first().key}") {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            SearchHeading(
                                if (section.filter == SearchFilter.ALL) "Результаты"
                                else section.filter.label()
                            )
                            if (
                                state.filter == SearchFilter.ALL &&
                                    section.filter != SearchFilter.ALL
                            )
                                TextButton({
                                    saveScroll()
                                    model.selectFilter(section.filter)
                                }) {
                                    Text(section.filter.allLabel())
                                }
                        }
                    }
                    items(unique, key = { it.key }) { item ->
                        SearchResultRow(
                            item,
                            artwork,
                            current,
                            model::play,
                            onMenu,
                            {
                                saveScroll()
                                onArtist(it)
                            },
                            {
                                saveScroll()
                                onAlbum(it)
                            },
                            {
                                saveScroll()
                                onPublicPlaylist(it)
                            },
                        )
                    }
                }
            }
            if (state.loadingMore)
                item(key = "search-more-loading") {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
            state.moreError?.let { error ->
                item(key = "search-more-error") { InlineError(error) { model.retryMore() } }
            }
            if (page?.next != null && !state.loadingMore && state.moreError == null)
                item(key = "search-more") { TextButton(model::more) { Text("Показать ещё") } }
            if (!state.loading && page?.items().orEmpty().isEmpty() && state.error == null)
                item(key = "search-empty") {
                    Text(
                        if (state.query.isBlank()) "Найдите музыку по названию или исполнителю"
                        else "Ничего не найдено",
                        Modifier.padding(vertical = 32.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
    }
}

@Composable
private fun SearchHeading(text: String) {
    Text(
        text.uppercase(),
        Modifier.padding(top = 8.dp, bottom = 12.dp),
        style = MaterialTheme.typography.bodySmall,
        letterSpacing = 2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
