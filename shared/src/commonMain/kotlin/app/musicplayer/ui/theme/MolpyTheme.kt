package app.musicplayer.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.sp
import app.musicplayer.appearance.AppTheme

val LocalAppTheme = staticCompositionLocalOf { AppTheme.DARK }

val LocalArtworkColors = staticCompositionLocalOf { ArtworkPalette.fromPixels(intArrayOf()) }

@Composable
fun MolpyTheme(colors: ArtworkColors, content: @Composable () -> Unit) =
    MolpyTheme(AppTheme.DARK, colors, content)

@Composable
fun MolpyTheme(theme: AppTheme, colors: ArtworkColors, content: @Composable () -> Unit) {
    val scheme =
        remember(theme, colors) {
            val light = theme == AppTheme.LIGHT
            val base = if (light) lightColorScheme() else darkColorScheme()
            val raised = if (light) Color.Black else Color.White
            base.copy(
                primary = Color(colors.accent),
                onPrimary = Color(colors.onAccent),
                secondary = Color(colors.accent),
                onSecondary = Color(colors.onAccent),
                background = Color(colors.background),
                onBackground = Color(colors.onBackground),
                surface = Color(colors.surface),
                surfaceTint = Color(colors.accent),
                surfaceDim = Color(colors.background),
                surfaceBright = lerp(Color(colors.surface), raised, 0.08f),
                surfaceContainerLowest = Color(colors.background),
                surfaceContainerLow = lerp(Color(colors.background), Color(colors.surface), 0.65f),
                surfaceContainer = Color(colors.surface),
                surfaceContainerHigh = lerp(Color(colors.surface), raised, 0.035f),
                surfaceContainerHighest = lerp(Color(colors.surface), raised, 0.065f),
                surfaceVariant = lerp(Color(colors.surface), raised, 0.035f),
                primaryContainer = lerp(Color(colors.surface), Color(colors.accent), 0.2f),
                onPrimaryContainer = Color(colors.onSurface),
                secondaryContainer = lerp(Color(colors.surface), Color(colors.accent), 0.2f),
                onSecondaryContainer = Color(colors.onSurface),
                onSurface = Color(colors.onSurface),
                onSurfaceVariant = Color(colors.onSurface).copy(alpha = 0.74f),
                tertiary = Color(colors.accent),
                onTertiary = Color(colors.onAccent),
                tertiaryContainer = lerp(Color(colors.surface), Color(colors.accent), 0.2f),
                onTertiaryContainer = Color(colors.onSurface),
                inverseSurface = Color(colors.onSurface),
                inverseOnSurface = Color(colors.surface),
                inversePrimary = Color(colors.onAccent),
                error = if (light) Color(0xffa32222) else Color(0xffffb4ab),
                onError = if (light) Color.White else Color(0xff690005),
                errorContainer = if (light) Color(0xffffdad6) else Color(0xff93000a),
                onErrorContainer = if (light) Color(0xff410002) else Color(0xffffdad6),
                scrim = Color.Black,
                outline = lerp(Color(colors.surface), Color(colors.onSurface), 0.55f),
                outlineVariant = lerp(Color(colors.surface), Color(colors.onSurface), 0.22f),
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
    CompositionLocalProvider(LocalAppTheme provides theme, LocalArtworkColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
