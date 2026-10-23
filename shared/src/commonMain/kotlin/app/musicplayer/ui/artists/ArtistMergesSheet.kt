package app.musicplayer.ui.artists

import androidx.compose.material3.*
import androidx.compose.runtime.*
import app.musicplayer.artists.*

@Composable
fun ArtistMergesSheet(
    artistId: ArtistId,
    directory: ArtistDirectory,
    onSplit: (ArtistId) -> Unit,
    onClose: () -> Unit,
) {
    val edges =
        remember(artistId, directory) {
            if (artistId !in directory.profiles) emptyList()
            else {
                val root = ArtistResolver.root(artistId, directory)
                directory.merges.values
                    .filter { ArtistResolver.root(it.origin, directory) == root }
                    .sortedByDescending { it.revision }
            }
        }
    ArtistSheetContent("Объединённые карточки", onClose) {
        Text(
            "Выберите связь, которую хотите отменить. Перед разделением покажем затронутые треки.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (edges.isEmpty()) Text("Объединений нет.")
        edges.forEach { edge ->
            ArtistOption(
                directory.profiles.getValue(edge.origin).name,
                "Объединена с ${directory.profiles.getValue(edge.target).name}",
                { onSplit(edge.origin) },
            )
        }
    }
}
