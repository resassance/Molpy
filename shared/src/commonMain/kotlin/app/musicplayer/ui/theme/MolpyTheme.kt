package app.musicplayer.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.sp

val LocalArtworkColors = staticCompositionLocalOf { ArtworkPalette.fromPixels(intArrayOf()) }

@Composable
fun MolpyTheme(colors: ArtworkColors, content: @Composable () -> Unit) {
    val scheme =
        remember(colors) {
            darkColorScheme(
                primary = Color(colors.accent),
                onPrimary = Color(colors.onAccent),
                secondary = Color(colors.accent),
                onSecondary = Color(colors.onAccent),
                background = Color(colors.background),
                onBackground = Color(colors.onBackground),
                surface = Color(colors.surface),
                onSurface = Color(colors.onSurface),
                onSurfaceVariant = Color(colors.onSurface).copy(alpha = 0.74f),
                outline = Color(colors.accent).copy(alpha = 0.35f),
            )
        }
    val largeText = LocalDensity.current.fontScale > 1.2f
    val type =
        remember(largeText) {
            Typography(
                headlineLarge =
                    TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontSize = if (largeText) 28.sp else 36.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-1).sp,
                    ),
                headlineMedium =
                    TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                titleLarge =
                    TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp),
                bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp),
                bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp),
            )
        }
    CompositionLocalProvider(LocalArtworkColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
