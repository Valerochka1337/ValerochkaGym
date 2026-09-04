package com.valerochka1337.valerochkagym.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Material tonal roles exported from the four brand seeds for light and dark appearances. */
private data class AccentTones(
    val lightPrimary: Color,
    val lightOnPrimary: Color,
    val lightContainer: Color,
    val lightOnContainer: Color,
    val darkPrimary: Color,
    val darkOnPrimary: Color,
    val darkContainer: Color,
    val darkOnContainer: Color,
)

private val BrandTones =
    mapOf(
        AccentColor.GREEN to
            AccentTones(
                lightPrimary = Color(0xFF005C33),
                lightOnPrimary = Color(0xFFFFFFFF),
                lightContainer = Color(0xFF9CF6BA),
                lightOnContainer = Color(0xFF00210F),
                darkPrimary = Color(0xFF80DA9F),
                darkOnPrimary = Color(0xFF00210F),
                darkContainer = Color(0xFF00522D),
                darkOnContainer = Color(0xFFB2FFCB),
            ),
        AccentColor.LIME to
            AccentTones(
                lightPrimary = Color(0xFF405500),
                lightOnPrimary = Color(0xFFFFFFFF),
                lightContainer = Color(0xFFD5F78A),
                lightOnContainer = Color(0xFF151F00),
                darkPrimary = Color(0xFFB9DB70),
                darkOnPrimary = Color(0xFF151F00),
                darkContainer = Color(0xFF3D4E00),
                darkOnContainer = Color(0xFFE1FF9D),
            ),
        AccentColor.CYAN to
            AccentTones(
                lightPrimary = Color(0xFF005365),
                lightOnPrimary = Color(0xFFFFFFFF),
                lightContainer = Color(0xFFA9EDFF),
                lightOnContainer = Color(0xFF001F27),
                darkPrimary = Color(0xFF54D6F4),
                darkOnPrimary = Color(0xFF001F27),
                darkContainer = Color(0xFF004E5D),
                darkOnContainer = Color(0xFFC1F3FF),
            ),
        AccentColor.CORAL to
            AccentTones(
                lightPrimary = Color(0xFF92301B),
                lightOnPrimary = Color(0xFFFFFFFF),
                lightContainer = Color(0xFFFFDAD1),
                lightOnContainer = Color(0xFF3B0902),
                darkPrimary = Color(0xFFFFB4A3),
                darkOnPrimary = Color(0xFF3B0902),
                darkContainer = Color(0xFF7D2B17),
                darkOnContainer = Color(0xFFFFDAD1),
            ),
    )

internal fun brandColorScheme(accent: AccentColor, darkTheme: Boolean): ColorScheme {
  val tones = checkNotNull(BrandTones[accent])
  return if (darkTheme) darkBrandColorScheme(tones) else lightBrandColorScheme(tones)
}

private fun lightBrandColorScheme(tones: AccentTones): ColorScheme =
    lightColorScheme(
        primary = tones.lightPrimary,
        onPrimary = tones.lightOnPrimary,
        primaryContainer = tones.lightContainer,
        onPrimaryContainer = tones.lightOnContainer,
        inversePrimary = tones.darkPrimary,
        secondary = tones.lightPrimary,
        onSecondary = tones.lightOnPrimary,
        secondaryContainer = tones.lightContainer,
        onSecondaryContainer = tones.lightOnContainer,
        tertiary = tones.lightPrimary,
        onTertiary = tones.lightOnPrimary,
        tertiaryContainer = tones.lightContainer,
        onTertiaryContainer = tones.lightOnContainer,
        background = Color(0xFFF8FBF7),
        onBackground = Color(0xFF171D19),
        surface = Color(0xFFF8FBF7),
        onSurface = Color(0xFF171D19),
        surfaceVariant = Color(0xFFDEE5DE),
        onSurfaceVariant = Color(0xFF35413A),
        inverseSurface = Color(0xFF2C322E),
        inverseOnSurface = Color(0xFFF0F3EE),
        outline = Color(0xFF66716A),
        outlineVariant = Color(0xFFBFC9C1),
        surfaceDim = Color(0xFFD8DBD6),
        surfaceBright = Color(0xFFF8FBF7),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF2F5F0),
        surfaceContainer = Color(0xFFECEFEA),
        surfaceContainerHigh = Color(0xFFE6E9E4),
        surfaceContainerHighest = Color(0xFFE0E3DE),
        primaryFixed = tones.lightContainer,
        primaryFixedDim = tones.darkPrimary,
        onPrimaryFixed = tones.lightOnContainer,
        onPrimaryFixedVariant = tones.darkOnPrimary,
        secondaryFixed = tones.lightContainer,
        secondaryFixedDim = tones.darkPrimary,
        onSecondaryFixed = tones.lightOnContainer,
        onSecondaryFixedVariant = tones.darkOnPrimary,
        tertiaryFixed = tones.lightContainer,
        tertiaryFixedDim = tones.darkPrimary,
        onTertiaryFixed = tones.lightOnContainer,
        onTertiaryFixedVariant = tones.darkOnPrimary,
    )

private fun darkBrandColorScheme(tones: AccentTones): ColorScheme =
    darkColorScheme(
        primary = tones.darkPrimary,
        onPrimary = tones.darkOnPrimary,
        primaryContainer = tones.darkContainer,
        onPrimaryContainer = tones.darkOnContainer,
        inversePrimary = tones.lightPrimary,
        secondary = tones.darkPrimary,
        onSecondary = tones.darkOnPrimary,
        secondaryContainer = tones.darkContainer,
        onSecondaryContainer = tones.darkOnContainer,
        tertiary = tones.darkPrimary,
        onTertiary = tones.darkOnPrimary,
        tertiaryContainer = tones.darkContainer,
        onTertiaryContainer = tones.darkOnContainer,
        background = Color(0xFF101412),
        onBackground = Color(0xFFE1E9E2),
        surface = Color(0xFF101412),
        onSurface = Color(0xFFE1E9E2),
        surfaceVariant = Color(0xFF3F4942),
        onSurfaceVariant = Color(0xFFE1E9E2),
        inverseSurface = Color(0xFFE1E9E2),
        inverseOnSurface = Color(0xFF27302A),
        outline = Color(0xFF95A098),
        outlineVariant = Color(0xFF3F4942),
        surfaceDim = Color(0xFF101412),
        surfaceBright = Color(0xFF353A36),
        surfaceContainerLowest = Color(0xFF0B0F0D),
        surfaceContainerLow = Color(0xFF181C19),
        surfaceContainer = Color(0xFF1C201D),
        surfaceContainerHigh = Color(0xFF262A27),
        surfaceContainerHighest = Color(0xFF313532),
        primaryFixed = tones.lightContainer,
        primaryFixedDim = tones.darkPrimary,
        onPrimaryFixed = tones.lightOnContainer,
        onPrimaryFixedVariant = tones.darkOnPrimary,
        secondaryFixed = tones.lightContainer,
        secondaryFixedDim = tones.darkPrimary,
        onSecondaryFixed = tones.lightOnContainer,
        onSecondaryFixedVariant = tones.darkOnPrimary,
        tertiaryFixed = tones.lightContainer,
        tertiaryFixedDim = tones.darkPrimary,
        onTertiaryFixed = tones.lightOnContainer,
        onTertiaryFixedVariant = tones.darkOnPrimary,
    )
