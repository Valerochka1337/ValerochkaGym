package com.valerochka1337.valerochkagym.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDarkTheme
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = remember(context, paletteMode, accent, darkTheme) {
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
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = GymTypography,
        shapes = GymShapes,
        content = content,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
