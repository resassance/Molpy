package app.musicplayer.ui.artists

import androidx.compose.runtime.Composable
import app.musicplayer.artists.*

@Composable
fun ArtistSelectionSheet(
    credits: List<ArtistCredit>,
    onSelect: (ArtistCredit) -> Unit,
    onClose: () -> Unit,
) {
    ArtistSheetContent("Исполнители", onClose) {
        credits.forEach { credit ->
            ArtistOption(
                credit.name,
                if (credit.source == null) "Музыка на устройстве" else null,
                { onSelect(credit) },
            )
        }
    }
}
