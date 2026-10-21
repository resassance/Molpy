package app.musicplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.model.*
import coil3.compose.AsyncImage

val LocalArtworkEpoch = staticCompositionLocalOf { 0L }

@Composable
fun TrackArtwork(
    artwork: Artwork,
    loader: ArtworkLoader,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        val data = artwork.localKey ?: artwork.remoteUrl
        if (data != null && artwork.kind != ArtworkKind.PLACEHOLDER) {
            val generation = if (artwork.localKey == null) LocalArtworkEpoch.current else 0L
            var retry by remember(data, generation) { mutableStateOf(0) }
            val request =
                remember(artwork, generation, retry, loader) {
                    loader.displayRequest(artwork, generation, retry)
                }
            key(generation, retry) {
                AsyncImage(
                    request,
                    description,
                    imageLoader = loader.imageLoader,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onSuccess = { loader.rememberDisplayResult(artwork, generation, it.result) },
                    onError = { if (retry == 0) retry = 1 },
                )
            }
        } else
            Icon(
                AppIcons.Library,
                description,
                Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}
