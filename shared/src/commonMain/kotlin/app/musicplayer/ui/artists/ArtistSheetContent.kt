package app.musicplayer.ui.artists

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.musicplayer.artists.*
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.model.*
import app.musicplayer.ui.components.*
import app.musicplayer.ui.present
import app.musicplayer.ui.theme.LocalAppTheme
import app.musicplayer.ui.theme.ThemeWindowAppearance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArtistSheetContent(
    title: String,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        ThemeWindowAppearance(LocalAppTheme.current)
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
internal fun ArtistOption(
    name: String,
    detail: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    artwork: Artwork? = null,
    loader: ArtworkLoader? = null,
) {
    TextButton(
        onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        contentPadding = PaddingValues(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (artwork != null && loader != null)
                TrackArtwork(artwork, loader, Modifier.size(40.dp))
            else Icon(AppIcons.Profile, null, Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail != null)
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }
    }
}

internal fun choiceName(choice: ArtistChoice, directory: ArtistDirectory): String =
    when (choice) {
        is ArtistChoice.Name -> choice.name
        is ArtistChoice.Catalog -> choice.profile.name
        is ArtistChoice.Existing ->
            directory.profiles[choice.id]?.let { ArtistResolver.profile(it.id, directory).name }
                ?: "Карточка недоступна"
    }

@Composable
internal fun ArtistError(error: AppError?) {
    if (error == null) return
    val message =
        when {
            error.operationId == "artists.editor.empty" -> "Добавьте хотя бы одного исполнителя."
            error.operationId == "artists.editor.stale" ->
                "Данные трека изменились. Проверьте состав и сохраните ещё раз."
            error.code == ErrorCode.INVALID_CONFIG ->
                "Карточки изменились. Откройте выбор исполнителей заново."
            else -> present(error).message
        }
    Text(
        message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}
