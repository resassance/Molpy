package app.musicplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
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
        if (data != null && artwork.kind != ArtworkKind.PLACEHOLDER)
            key(LocalArtworkEpoch.current) {
                AsyncImage(
                    data,
                    description,
                    imageLoader = loader.imageLoader,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        else
            Icon(
                AppIcons.Library,
                description,
                Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}
