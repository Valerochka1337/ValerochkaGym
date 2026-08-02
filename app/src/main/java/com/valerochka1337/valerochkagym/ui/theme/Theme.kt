package com.valerochka1337.valerochkagym.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Схема цветов под выбранный акцент. Нейтральная тёмная база не зависит от акцента — меняются
 * только акцентные роли; правило единственного акцента сохраняется: secondary/tertiary остаются
 * производными того же оттенка, а не вторым цветом.
 */
private fun gymColorScheme(accent: AccentColor): ColorScheme = darkColorScheme(
    primary = accent.primary,
    onPrimary = accent.ink,
    primaryContainer = accent.container,
    onPrimaryContainer = accent.light,
    // Single-accent policy: secondary/tertiary are accent-derived, never a second hue.
    secondary = accent.light,
    onSecondary = accent.ink,
    secondaryContainer = accent.container,
    onSecondaryContainer = accent.light,
    tertiary = accent.light,
    onTertiary = accent.ink,
    tertiaryContainer = accent.container,
    onTertiaryContainer = accent.light,
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

/** App theme. Dark only, built on Material 3 Expressive; [accent] chosen by the user in settings. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GymTheme(
    accent: AccentColor = AccentColor.DEFAULT,
    content: @Composable () -> Unit,
) {
    val colorScheme = remember(accent) { gymColorScheme(accent) }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = GymTypography,
        shapes = GymShapes,
        content = content,
    )
}
