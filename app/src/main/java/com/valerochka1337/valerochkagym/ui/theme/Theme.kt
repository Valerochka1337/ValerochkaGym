package com.valerochka1337.valerochkagym.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val GymColorScheme = darkColorScheme(
    primary = GymGreen,
    onPrimary = OnAccent,
    primaryContainer = GymGreenContainer,
    onPrimaryContainer = GymGreenLight,
    secondary = Peach,
    onSecondary = OnAccent,
    secondaryContainer = GymGreenContainer,
    onSecondaryContainer = GymGreenLight,
    tertiary = Peach,
    onTertiary = OnAccent,
    tertiaryContainer = PeachContainer,
    onTertiaryContainer = Peach,
    background = GymBlack,
    onBackground = TextPrimary,
    surface = GymSurface,
    onSurface = TextPrimary,
    surfaceVariant = GymSurfaceTop,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = SurfaceContainerLowest,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    outline = TextTertiary,
)

/** App theme. Dark only, built on Material 3 Expressive. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GymTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = GymColorScheme,
        typography = GymTypography,
        content = content,
    )
}
