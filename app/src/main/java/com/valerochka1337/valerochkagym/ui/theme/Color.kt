package com.valerochka1337.valerochkagym.ui.theme

import androidx.compose.ui.graphics.Color

// Neutral dark base — depth comes from tonal surfaceContainer* steps, not tints or gradients.
val GymBlack = Color(0xFF0C0D0F)
val GymSurface = Color(0xFF15171A)
val GymSurfaceTop = Color(0xFF1C1F23)

// The accent itself is not here: it is user-selectable and lives in AccentColor.kt.

val TextPrimary = Color(0xFFF1F3F4)
val TextSecondary = Color(0xFF9CA3A8)
val TextTertiary = Color(0xFF565C63)

/** Background of the launcher icon — used to preview the icon inside the accent picker. */
val LauncherIconBackground = Color(0xFF121212)

// Elevation ramp for M3 surfaceContainer* roles — neutral, from GymBlack upward.
val SurfaceContainerLowest = Color(0xFF0A0B0D)
val SurfaceContainerLow = Color(0xFF131518)
val SurfaceContainer = Color(0xFF17191D)
val SurfaceContainerHigh = Color(0xFF1F2226)
val SurfaceContainerHighest = Color(0xFF272A2F)
