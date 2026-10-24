package app.musicplayer.ui.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import app.musicplayer.ui.components.AppIcons

@Composable
internal fun MolpyBottomNavigation(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    NavigationBar(modifier = modifier, containerColor = Color.Transparent) {
        AppTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                colors =
                    NavigationBarItemDefaults.colors(
                        indicatorColor = Color.Transparent,
                        selectedIconColor = colors.onSurface,
                        selectedTextColor = colors.onSurface,
                        unselectedIconColor = colors.onSurfaceVariant,
                        unselectedTextColor = colors.onSurfaceVariant,
                    ),
                icon = {
                    Icon(
                        when (tab) {
                            AppTab.LIBRARY -> AppIcons.Library
                            AppTab.SEARCH -> AppIcons.Search
                            AppTab.PROFILE -> AppIcons.Profile
                        },
                        contentDescription = null,
                    )
                },
                label = {
                    Text(
                        tab.label,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}
