package app.musicplayer.ui.components

import androidx.compose.runtime.*
import app.musicplayer.artists.*
import app.musicplayer.model.*

val LocalArtistDirectory = staticCompositionLocalOf { ArtistDirectory() }

@Composable
fun trackPresentation(track: Track): TrackPresentation =
    ArtistResolver.present(track, LocalArtistDirectory.current)

@Composable
fun artistName(artist: ArtistRef): String {
    val directory = LocalArtistDirectory.current
    val id = artist.source?.let { directory.aliases[ArtistAliasKey(ArtistResolver.sourceKey(it))] }
    return id?.let { ArtistResolver.profile(it, directory).name } ?: artist.name
}
