package app.musicplayer.ui.artists

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import app.musicplayer.artists.*
import app.musicplayer.model.SourceRef
import app.musicplayer.ui.components.*
import app.musicplayer.ui.profile.formatListeningTime
import io.ktor.http.encodeURLPathPart

@Composable
fun ArtistMergeSheet(
    state: ArtistMergeState,
    directory: ArtistDirectory,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
    onPreferredSource: ((SourceRef) -> Unit)? = null,
) {
    val merge = state.merge
    val split = state.split
    val profiles =
        remember(merge, directory) {
            if (merge == null) emptyList()
            else
                directory.profiles.values
                    .filter {
                        ArtistResolver.root(it.id, directory) in
                            setOf(merge.origin, merge.target) && it.source != null
                    }
                    .distinctBy { it.source }
        }
    val uri = LocalUriHandler.current
    ArtistSheetContent(
        if (split != null) "Отменить объединение" else "Объединить артистов",
        onClose,
    ) {
        if (merge != null) {
            val origin = directory.profiles[merge.origin]?.name.orEmpty()
            val target = directory.profiles[merge.target]?.name.orEmpty()
            Text("Объединяем: $origin", style = MaterialTheme.typography.titleMedium)
            Text("Итоговая карточка: $target")
            Text("Сохранённых треков: ${merge.trackCount}")
            Text("Время прослушивания: ${formatListeningTime(merge.playedMs)}")
            if (profiles.size > 1) {
                Text(
                    "Выберите профиль для итоговой карточки",
                    style = MaterialTheme.typography.titleMedium,
                )
                profiles.forEach { profile ->
                    val source = requireNotNull(profile.source)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            merge.preferredSource == source,
                            { onPreferredSource?.invoke(source) },
                            enabled = !state.saving && onPreferredSource != null,
                            modifier = Modifier.size(48.dp),
                        )
                        Text(profile.name, Modifier.weight(1f))
                        if (
                            source.provider in setOf("youtube", "youtube_music") &&
                                source.kind == "artist"
                        )
                            TextButton(
                                {
                                    runCatching {
                                        uri.openUri(
                                            "https://music.youtube.com/channel/" +
                                                source.id.encodeURLPathPart()
                                        )
                                    }
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text("Открыть")
                            }
                    }
                }
            }
            Text(
                "Связи можно будет разделить в меню артиста.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (split != null) {
            Text("Отделятся карточки: ${split.detached.joinToString { it.name }}")
            Text("Сохранённых треков: ${split.trackCount}")
            Text(
                "Явные правки отдельных треков сохранятся.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.stale)
            Text(
                "Охват изменился. Проверьте обновлённые данные перед подтверждением.",
                color = MaterialTheme.colorScheme.primary,
            )
        ArtistError(state.error)
        if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (merge != null || split != null)
            MenuAction(
                if (split != null) "Разделить карточки" else "Объединить карточки",
                AppIcons.Check,
                onConfirm,
                !state.saving &&
                    state.error == null &&
                    (split != null || profiles.size <= 1 || merge?.preferredSource != null),
            )
    }
}
