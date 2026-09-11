package com.valerochka1337.valerochkagym.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Semantic result tokens remain stable when the user chooses a non-green dynamic accent. */
data class CoachActionColors(
    val acceptedContainer: Color,
    val onAcceptedContainer: Color,
    val rejectedContainer: Color,
    val onRejectedContainer: Color,
)

val LocalCoachActionColors =
    staticCompositionLocalOf {
      CoachActionColors(
          acceptedContainer = Color(0xFF1D5138),
          onAcceptedContainer = Color(0xFFE5F7EA),
          rejectedContainer = Color(0xFF70211E),
          onRejectedContainer = Color(0xFFFFDAD6),
      )
    }

/** Material 3 Expressive theme with independent appearance and palette preferences. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GymTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    paletteMode: PaletteMode = PaletteMode.SYSTEM,
    accent: AccentColor = AccentColor.DEFAULT,
    content: @Composable () -> Unit,
) {
  val systemDarkTheme = isSystemInDarkTheme()
  val darkTheme =
      when (themeMode) {
        ThemeMode.SYSTEM -> systemDarkTheme
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
      }
  val context = LocalContext.current
  val colorScheme =
      remember(context, paletteMode, accent, darkTheme) {
        if (paletteMode == PaletteMode.SYSTEM) {
          if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
          brandColorScheme(paletteMode.accent ?: accent, darkTheme)
        }
      }
  val view = LocalView.current
  SideEffect {
    context.findActivity()?.window?.let { window ->
      WindowCompat.getInsetsController(window, view).apply {
        isAppearanceLightStatusBars = !darkTheme
        isAppearanceLightNavigationBars = !darkTheme
      }
    }
  }
  CompositionLocalProvider(LocalCoachActionColors provides LocalCoachActionColors.current) {
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = GymTypography,
        shapes = GymShapes,
        content = content,
    )
  }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
      is Activity -> this
      is ContextWrapper -> baseContext.findActivity()
      else -> null
    }
