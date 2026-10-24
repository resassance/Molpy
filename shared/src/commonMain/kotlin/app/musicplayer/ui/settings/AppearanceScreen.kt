package app.musicplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.musicplayer.appearance.AppTheme
import app.musicplayer.ui.components.ScreenHeader
import app.musicplayer.ui.present
import app.musicplayer.ui.theme.AppThemePalette
import app.musicplayer.ui.theme.ArtworkPalette

@Composable
fun AppearanceScreen(model: AppearanceModel, onBack: () -> Unit) {
    val state by model.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Оформление", onBack)
        LazyColumn(
            modifier = Modifier.weight(1f).selectableGroup(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(AppTheme.entries.size) { index ->
                val theme = AppTheme.entries[index]
                val preview = AppThemePalette.colors(theme, ArtworkPalette.fromPixels(intArrayOf()))
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 88.dp)
                        .selectable(
                            selected = state.theme == theme,
                            enabled = state.initialized && !state.saving,
                            role = Role.RadioButton,
                            onClick = {
                                model.choose(theme)
                            },
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier.size(width = 80.dp, height = 64.dp)
                            .background(Color(preview.background), RoundedCornerShape(12.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(12.dp),
                            )
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier.fillMaxWidth(0.65f)
                                .height(5.dp)
                                .background(Color(preview.onBackground), RoundedCornerShape(3.dp))
                        )
                        Box(
                            Modifier.fillMaxWidth()
                                .weight(1f)
                                .background(Color(preview.surface), RoundedCornerShape(4.dp))
                        )
                        Box(
                            Modifier.width(24.dp)
                                .height(5.dp)
                                .background(Color(preview.accent), RoundedCornerShape(3.dp))
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        when (theme) {
                            AppTheme.DARK -> "Тёмная"
                            AppTheme.CLASSIC -> "Классическая"
                            AppTheme.LIGHT -> "Светлая"
                        },
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    RadioButton(
                        selected = state.theme == theme,
                        onClick = null,
                        enabled = !state.saving,
                    )
                }
            }
            state.error?.let { error ->
                item {
                    Column {
                        Text(present(error).message, color = MaterialTheme.colorScheme.error)
                        TextButton(
                            enabled = !state.saving,
                            onClick = model::retry,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Повторить")
                        }
                    }
                }
            }
        }
    }
}
