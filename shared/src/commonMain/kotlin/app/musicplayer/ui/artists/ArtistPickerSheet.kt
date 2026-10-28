package app.musicplayer.ui.artists

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import app.musicplayer.artists.*
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.ui.components.*

@Composable
fun ArtistPickerSheet(
    query: String,
    results: LoadState<List<ArtistCatalogHit>>,
    directory: ArtistDirectory,
    onQuery: (String) -> Unit,
    onSelect: (ArtistChoice) -> Unit,
    onClose: () -> Unit,
    excluded: Set<ArtistId> = emptySet(),
    allowName: Boolean = true,
    title: String = "Выбрать исполнителя",
    artwork: ArtworkLoader? = null,
    matchingName: String? = null,
) {
    val local =
        remember(directory, query, excluded, matchingName) {
            artistPickerCandidates(directory, query, excluded, matchingName)
        }
    ArtistSheetContent(title, onClose) {
        OutlinedTextField(
            query,
            onQuery,
            Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Имя или профиль исполнителя") },
        )
        if (allowName && query.isNotBlank()) {
            Text(query.trim(), style = MaterialTheme.typography.bodyLarge)
            MenuAction(
                "Использовать имя без ссылки",
                AppIcons.Edit,
                { onSelect(ArtistChoice.Name(query)) },
                query.trim().length in 1..200,
            )
            if (query.trim().length > 200)
                Text("В имени может быть до 200 символов.", color = MaterialTheme.colorScheme.error)
        }
        if (local.isNotEmpty()) {
            Text("На устройстве", style = MaterialTheme.typography.titleMedium)
            local.forEach { profile ->
                ArtistOption(
                    profile.name,
                    if (profile.source == null) "Локальная карточка" else "Связана с профилем",
                    { onSelect(ArtistChoice.Existing(profile.id)) },
                    loader = artwork,
                    source = profile.source,
                )
            }
        }
        if (allowName) {
            HorizontalDivider()
            Text("YouTube Music", style = MaterialTheme.typography.titleMedium)
            if (results.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            ArtistError(results.error)
            results.data.orEmpty().forEach { hit ->
                ArtistOption(
                    hit.name,
                    "Музыкальный профиль",
                    { onSelect(ArtistChoice.Catalog(hit)) },
                    artwork = hit.artwork,
                    loader = artwork,
                    source = hit.source,
                )
            }
            if (query.trim().length < 2)
                Text(
                    "Введите хотя бы два символа для поиска профиля.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else if (!results.loading && results.error == null && results.data?.isEmpty() == true)
                Text(
                    "Профили не найдены. Можно сохранить только имя.",
                    style = MaterialTheme.typography.bodySmall,
                )
        } else if (local.isEmpty())
            Text(
                if (query.isBlank())
                    "Других карточек с таким же именем нет. Найдите нужную по имени."
                else "Подходящих карточек нет."
            )
    }
}

internal fun artistPickerCandidates(
    directory: ArtistDirectory,
    query: String,
    excluded: Set<ArtistId>,
    matchingName: String? = null,
): List<ArtistProfile> {
    val excludedRoots =
        excluded
            .filter { it in directory.profiles }
            .map { ArtistResolver.root(it, directory) }
            .toSet()
    val search = ArtistResolver.normalizedName(query)
    val suggested = matchingName?.let(ArtistResolver::normalizedName)
    return directory.profiles.keys
        .map { ArtistResolver.root(it, directory) }
        .distinct()
        .filter { it !in excludedRoots }
        .map { ArtistResolver.profile(it, directory) }
        .filter {
            val name = ArtistResolver.normalizedName(it.name)
            if (search.isNotEmpty()) name.contains(search)
            else suggested == null || name == suggested
        }
        .sortedBy { ArtistResolver.normalizedName(it.name) }
}
