package app.musicplayer.ui.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
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
    var previousError by remember(state.query, state.filter) { mutableStateOf(state.error) }
    LaunchedEffect(list, state.error) {
        if (state.error != null && state.error != previousError) list.scrollToItem(0)
        previousError = state.error
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            TextField(
                query,
                { query = it },
                Modifier.fillMaxWidth().heightIn(min = 56.dp),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                placeholder = { Text("Название или исполнитель") },
                leadingIcon = { Icon(AppIcons.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty())
                        IconButton(
                            onClick = {
                                query = ""
                                model.search("")
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
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
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchFilter.entries.forEach { filter ->
                    val requester = remember { BringIntoViewRequester() }
                    var placed by remember { mutableStateOf(false) }
                    LaunchedEffect(state.filter, placed) {
                        if (placed && state.filter == filter) requester.bringIntoView()
                    }
                    FilterChip(
                        modifier =
                            Modifier.heightIn(min = 48.dp)
                                .bringIntoViewRequester(requester)
                                .onPlaced { placed = true },
                        shape = RoundedCornerShape(50),
                        border = null,
                        colors =
                            FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                labelColor = MaterialTheme.colorScheme.onSurface,
                                selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                                selectedLabelColor = MaterialTheme.colorScheme.surface,
                            ),
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
            val openArtist: (ArtistRef) -> Unit = {
                saveScroll()
                onArtist(it)
            }
            val openAlbum: (AlbumRef) -> Unit = {
                saveScroll()
                onAlbum(it)
            }
            val openPlaylist: (PublicPlaylistRef) -> Unit = {
                saveScroll()
                onPublicPlaylist(it)
            }
            page
                ?.topResult
                ?.takeIf { state.filter == SearchFilter.ALL }
                ?.let { top ->
                    item(key = "search-top-heading") {
                        Text(
                            "Лучший результат",
                            Modifier.padding(bottom = 4.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item(key = top.key) {
                        SearchResultRow(
                            top,
                            artwork,
                            current,
                            model::play,
                            onMenu,
                            openArtist,
                            openAlbum,
                            openPlaylist,
                        )
                    }
                }
            val sections = page?.displaySections(state.filter).orEmpty()
            sections.forEach { section ->
                item(key = "section:${section.filter}") {
                    SearchSectionHeading(section.filter, state.filter == SearchFilter.ALL) {
                        saveScroll()
                        model.selectFilter(section.filter)
                    }
                }
                if (state.filter == SearchFilter.ALL && section.filter == SearchFilter.ALBUMS) {
                    item(key = "shelf:${section.filter}") {
                        key(state.query) {
                            SearchAlbumShelf(
                                section.items.filterIsInstance<SearchItem.AlbumItem>(),
                                artwork,
                                openAlbum,
                            )
                        }
                    }
                } else {
                    items(section.items, key = { it.key }) { result ->
                        SearchResultRow(
                            result,
                            artwork,
                            current,
                            model::play,
                            onMenu,
                            openArtist,
                            openAlbum,
                            openPlaylist,
                            showType = false,
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
private fun SearchSectionHeading(filter: SearchFilter, showAll: Boolean, onAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(filter.label(), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        if (showAll) {
            TextButton(
                onAll,
                Modifier.heightIn(min = 48.dp).semantics { contentDescription = filter.allLabel() },
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
            ) {
                Text("Все")
                Icon(AppIcons.Down, null, Modifier.padding(start = 4.dp).size(20.dp).rotate(-90f))
            }
        }
    }
}
