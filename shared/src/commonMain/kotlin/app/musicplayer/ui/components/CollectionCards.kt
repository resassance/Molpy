package app.musicplayer.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.model.*

fun trackCount(count: Int): String {
    val suffix =
        when {
            count % 100 in 11..14 -> "треков"
            count % 10 == 1 -> "трек"
            count % 10 in 2..4 -> "трека"
            else -> "треков"
        }
    return "$count $suffix"
}

fun formatBytes(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> "${bytes/1024} КБ"
        bytes < 1024L * 1024 * 1024 -> "${bytes/(1024*1024)} МБ"
        else -> "${bytes/(1024*1024*1024)} ГБ"
    }

@Composable
fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = if (onBack == null) 24.dp else 8.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        onBack?.let { IconButton(it) { Icon(AppIcons.Back, "Назад") } }
        Text(
            title,
            Modifier.weight(1f),
            style =
                if (onBack == null) MaterialTheme.typography.headlineLarge
                else MaterialTheme.typography.headlineMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        action?.invoke()
    }
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        if (action != null && onAction != null)
            TextButton(onAction) {
                Text(action)
                Icon(AppIcons.Down, null, Modifier.size(18.dp).rotate(-90f))
            }
    }
}

@Composable
fun PlaylistCollage(covers: List<Artwork>, artwork: ArtworkLoader, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        repeat(2) { row ->
            Row(Modifier.weight(1f)) {
                repeat(2) { column ->
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        covers.getOrNull(row * 2 + column)?.let {
                            TrackArtwork(it, artwork, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ArtistAvatar(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val letter = name.firstOrNull { it.isLetter() }
        if (letter != null)
            Text(letter.uppercase(), fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurface)
        else Icon(AppIcons.Profile, null, Modifier.size(28.dp))
    }
}
