package com.valerochka1337.valerochkagym.ui.theme

import androidx.compose.ui.graphics.Color

// Neutral dark base — depth comes from tonal surfaceContainer* steps, not tints or gradients.
val GymBlack = Color(0xFF0C0D0F)
val GymSurface = Color(0xFF15171A)
val GymSurfaceTop = Color(0xFF1C1F23)

// Signature accent green, matching the app icon (#3DDC84). The single accent of the app.
val GymGreen = Color(0xFF3DDC84)
val GymGreenLight = Color(0xFF6FE9A6)

val TextPrimary = Color(0xFFF1F3F4)
val TextSecondary = Color(0xFF9CA3A8)
val TextTertiary = Color(0xFF565C63)

/** Dark ink used on top of the bright green accent (icon inversion tone). */
val OnAccent = Color(0xFF05130B)

// Green-derived container (selected states, filled chips, NavigationBar pill).
val GymGreenContainer = Color(0xFF123A24)

// Elevation ramp for M3 surfaceContainer* roles — neutral, from GymBlack upward.
val SurfaceContainerLowest = Color(0xFF0A0B0D)
val SurfaceContainerLow = Color(0xFF131518)
val SurfaceContainer = Color(0xFF17191D)
val SurfaceContainerHigh = Color(0xFF1F2226)
val SurfaceContainerHighest = Color(0xFF272A2F)
