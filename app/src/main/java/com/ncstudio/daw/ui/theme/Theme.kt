package com.ncstudio.daw.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StudioColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = OnAccent,
    primaryContainer = Color(0xFF3D2300),
    onPrimaryContainer = AccentHot,
    secondary = ElectricLime,
    onSecondary = OnAccent,
    secondaryContainer = Color(0xFF143820),
    onSecondaryContainer = ElectricLime,
    tertiary = StudioPurple,
    onTertiary = Color(0xFF140B1F),
    background = StudioBackground,
    onBackground = TextPrimary,
    surface = StudioSurface,
    onSurface = TextPrimary,
    surfaceVariant = StudioCard,
    onSurfaceVariant = TextSecondary,
    outline = StudioCardBorder,
    error = ClipRed,
    onError = Color(0xFF3D0D0A)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Pro Audio DAW locks intentional high-contrast studio dark palette
    content: @Composable () -> Unit
) {
    // Overlay UI Protocol fonts: bundled Archivo Variable + JetBrains Mono.
    com.overlay.studio.ui.StudioFonts.ui = ArchivoFamily
    com.overlay.studio.ui.StudioFonts.mono = JetBrainsMonoFamily
    MaterialTheme(
        colorScheme = StudioColorScheme,
        typography = Typography,
        content = content
    )
}
