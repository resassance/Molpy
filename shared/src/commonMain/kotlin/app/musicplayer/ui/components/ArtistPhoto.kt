package app.musicplayer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import app.musicplayer.artwork.ArtworkLoader
import app.musicplayer.model.*
import app.musicplayer.ui.artists.ArtistArtworkCoordinator

data class ArtistArtworkViewport(val active: Boolean = true, val bounds: Rect? = null)

val LocalArtistArtworkCoordinator = staticCompositionLocalOf<ArtistArtworkCoordinator?> { null }
val LocalArtistArtworkViewport = staticCompositionLocalOf { ArtistArtworkViewport() }

@Composable
fun ArtistPhoto(
    name: String,
    source: SourceRef?,
    artwork: ArtworkLoader,
    modifier: Modifier = Modifier,
    initial: Artwork? = null,
) {
    val coordinator = LocalArtistArtworkCoordinator.current
    val viewport = LocalArtistArtworkViewport.current
    val owner = remember { Any() }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    var rootBounds by remember { mutableStateOf<Rect?>(null) }
    val visible =
        viewport.active &&
            bounds?.let { item ->
                val area = viewport.bounds ?: rootBounds
                area != null && item.width > 0 && item.height > 0 && item.overlaps(area)
            } == true
    DisposableEffect(coordinator, source, visible) {
        if (visible && source != null) coordinator?.updateInterest(owner, setOf(source))
        onDispose { coordinator?.releaseInterest(owner) }
    }
    val states = coordinator?.state?.collectAsState()?.value
    val photo = states?.get(source)?.artwork ?: initial
    Box(
        modifier.clip(CircleShape).onGloballyPositioned { coordinates ->
            bounds = coordinates.boundsInWindow()
            var root = coordinates
            while (root.parentLayoutCoordinates != null) root = root.parentLayoutCoordinates!!
            val position = root.positionInWindow()
            rootBounds =
                Rect(
                    position.x,
                    position.y,
                    position.x + root.size.width,
                    position.y + root.size.height,
                )
        }
    ) {
        if (
            photo != null &&
                photo.kind != ArtworkKind.PLACEHOLDER &&
                (!photo.remoteUrl.isNullOrBlank() || !photo.localKey.isNullOrBlank())
        )
            TrackArtwork(photo, artwork, Modifier.fillMaxSize(), name)
        else ArtistAvatar(name, Modifier.fillMaxSize())
    }
}
