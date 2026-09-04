package com.valerochka1337.valerochkagym.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorSchemeTest {

  @Test
  fun `brand schemes keep normal text pairs at aaa contrast`() {
    AccentColor.entries.forEach { accent ->
      listOf(false, true).forEach { dark ->
        val scheme = brandColorScheme(accent, dark)
        val pairs =
            listOf(
                "background" to (scheme.onBackground to scheme.background),
                "surface" to (scheme.onSurface to scheme.surface),
                "surfaceVariant" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
                "primary" to (scheme.onPrimary to scheme.primary),
                "primaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                "secondary" to (scheme.onSecondary to scheme.secondary),
                "secondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
            )
        pairs.forEach { (role, colors) ->
          val ratio = contrast(colors.first, colors.second)
          assertTrue(
              "${accent.id} ${if (dark) "dark" else "light"} $role: $ratio < 7.0",
              ratio >= 7.0,
          )
        }
      }
    }
  }

  @Test
  fun `saved appearance ids have safe system fallbacks`() {
    assertTrue(ThemeMode.fromId("unknown") == ThemeMode.SYSTEM)
    assertTrue(PaletteMode.fromId("unknown") == PaletteMode.SYSTEM)
  }

  private fun contrast(foreground: Color, background: Color): Double {
    val light = max(luminance(foreground), luminance(background))
    val dark = min(luminance(foreground), luminance(background))
    return (light + 0.05) / (dark + 0.05)
  }

  private fun luminance(color: Color): Double {
    fun channel(value: Float): Double {
      val srgb = value.toDouble()
      return if (srgb <= 0.04045) srgb / 12.92 else ((srgb + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) +
        0.7152 * channel(color.green) +
        0.0722 * channel(color.blue)
  }
}
