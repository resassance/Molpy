package app.musicplayer.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
import app.musicplayer.model.*
import app.musicplayer.ui.components.*

@Composable
fun SearchScreen(
    model: SearchModel,
    artwork: ArtworkLoader,
    current: TrackId?,
    onMenu: (Track) -> Unit,
) {
    val state by model.state.collectAsState()
    var query by rememberSaveable { mutableStateOf(model.query.value) }
    var submitted by rememberSaveable { mutableStateOf(model.query.value) }
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit() {
        keyboard?.hide()
        submitted = query
        model.search(query)
    }
    LaunchedEffect(Unit) {
        if (model.query.value.isEmpty() && submitted.isNotBlank()) model.search(submitted)
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
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
                            submitted = ""
                            model.search("")
                        }) {
                            Icon(AppIcons.Close, "Очистить поиск")
                        }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
            )
        }
        LazyColumn(
            Modifier.weight(1f),
            state = rememberLazyListState(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 16.dp),
        ) {
            if (state.loading)
                item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
            state.error?.let { error -> item { InlineError(error) { model.search(query) } } }
            if (state.data.orEmpty().isNotEmpty())
                item {
                    Text(
                        "РЕЗУЛЬТАТЫ",
                        Modifier.padding(top = 8.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        letterSpacing = 2.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            itemsIndexed(
                state.data.orEmpty(),
                key = { index, track -> "$index:${track.id.value}" },
            ) { index, track ->
                TrackRow(
                    track,
                    artwork,
                    { model.play(index) },
                    { onMenu(track) },
                    selected = track.id == current,
                )
            }
            if (!state.loading && state.data.orEmpty().isEmpty() && state.error == null)
                item {
                    Text(
                        if (query.isBlank()) "Найдите музыку по названию или исполнителю"
                        else "Ничего не найдено",
                        Modifier.padding(vertical = 32.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
    }
}
