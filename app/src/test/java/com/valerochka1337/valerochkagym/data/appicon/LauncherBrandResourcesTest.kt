package com.valerochka1337.valerochkagym.data.appicon

import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.R
import com.valerochka1337.valerochkagym.ui.theme.AccentColor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class LauncherBrandResourcesTest {
  private val context = ApplicationProvider.getApplicationContext<Application>()

  @Test
  fun `launcher aliases retain their identity and all use the new adaptive mark`() {
    val activities =
        context.packageManager
            .getPackageInfo(
                context.packageName,
                PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            .activities
            .orEmpty()
            .associateBy { it.name }
    AccentColor.entries.forEach { accent ->
      val alias = requireNotNull(activities["${context.packageName}.${accent.aliasName}"])
      assertEquals("Yarumo coach", alias.loadLabel(context.packageManager))
      assertTrue(context.getDrawable(alias.icon) is AdaptiveIconDrawable)
      val adaptive = context.getDrawable(alias.icon) as AdaptiveIconDrawable
      val canonical = render(R.drawable.ic_launcher_foreground)
      val actual = Bitmap.createBitmap(324, 324, Bitmap.Config.ARGB_8888)
      adaptive.foreground.setBounds(0, 0, 324, 324)
      adaptive.foreground.draw(Canvas(actual))
      assertTrue(canonical.sameAs(actual))
    }
  }

  @Test
  fun `colored and themed marks preserve geometry within the circular adaptive safe zone`() {
    val colored = render(R.drawable.ic_launcher_foreground)
    val mono = render(R.drawable.ic_launcher_monochrome)
    var pixels = 0
    val colors = mutableSetOf<Int>()
    for (y in 0 until 324) for (x in 0 until 324) {
      val pixel = colored.getPixel(x, y)
      assertEquals(Color.alpha(pixel), Color.alpha(mono.getPixel(x, y)))
      if (Color.alpha(pixel) > 0) {
        pixels++
        val dx = (x + 0.5f) / 3 - 54
        val dy = (y + 0.5f) / 3 - 54
        assertTrue("Mark clips the safe circle at $x,$y", dx * dx + dy * dy <= 33 * 33)
        if (Color.alpha(pixel) == 255) colors.add(pixel)
      }
    }
    assertTrue(pixels > 1000)
    assertTrue(colors.contains(Color.rgb(128, 169, 249)))
    assertTrue(colors.contains(Color.rgb(185, 138, 231)))
  }

  @Test
  fun `adaptive background is opaque across the full layer`() {
    val bitmap = render(R.drawable.ic_launcher_background)
    for (y in 0 until 324) for (x in 0 until 324) assertEquals(
        255,
        Color.alpha(bitmap.getPixel(x, y)),
    )
  }

  private fun render(id: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(324, 324, Bitmap.Config.ARGB_8888)
    requireNotNull(context.getDrawable(id)).apply {
      setBounds(0, 0, 324, 324)
      draw(Canvas(bitmap))
    }
    return bitmap
  }
}
