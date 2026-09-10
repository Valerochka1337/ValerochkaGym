package com.valerochka1337.valerochkagym.data.appicon

import android.app.Application
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.VectorDrawable
import android.view.Gravity
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.PathParser
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.R
import com.valerochka1337.valerochkagym.ui.theme.AccentColor
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** Resource-level contract for the approved Yarumo launcher mark and its immutable aliases. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LauncherBrandResourcesTest {

  private val context = ApplicationProvider.getApplicationContext<Application>()

  @Test
  fun `launcher aliases resolve the Yarumo adaptive icon and product label`() {
    val activities =
        context.packageManager
            .getPackageInfo(
                context.packageName,
                PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            .activities
            .orEmpty()
            .associateBy { it.name }

    val expectedIcons =
        mapOf(
            AccentColor.GREEN to R.mipmap.ic_launcher,
            AccentColor.LIME to R.mipmap.ic_launcher_lime,
            AccentColor.CYAN to R.mipmap.ic_launcher_cyan,
            AccentColor.CORAL to R.mipmap.ic_launcher_coral,
        )

    expectedIcons.forEach { (accent, icon) ->
      val alias = requireNotNull(activities["${context.packageName}.${accent.aliasName}"])
      assertEquals(icon, alias.icon)
      assertEquals("Yarumo coach", alias.loadLabel(context.packageManager))
    }
  }

  @Test
  fun `every adaptive foreground preserves the approved mark inside equal safe insets`() {
    val adaptiveForegrounds =
        mapOf(
            R.mipmap.ic_launcher to R.drawable.ic_launcher_foreground,
            R.mipmap.ic_launcher_round to R.drawable.ic_launcher_foreground,
            R.mipmap.ic_launcher_lime to R.drawable.ic_launcher_foreground_lime,
            R.mipmap.ic_launcher_cyan to R.drawable.ic_launcher_foreground_cyan,
            R.mipmap.ic_launcher_coral to R.drawable.ic_launcher_foreground_coral,
        )

    adaptiveForegrounds.forEach { (adaptiveIcon, foreground) ->
      val adaptive = context.resources.getXml(adaptiveIcon).atStartTag()
      assertEquals("adaptive-icon", adaptive.name)
      val layers = adaptive.adaptiveLayers()
      assertEquals(
          foreground,
          layers.getValue("foreground"),
      )
      assertEquals(
          R.drawable.ic_launcher_monochrome,
          layers.getValue("monochrome"),
      )

      val inset = context.resources.getXml(foreground).atStartTag()
      assertEquals("inset", inset.name)
      assertEquals(
          15f,
          inset.getAttributeValue(ANDROID_NAMESPACE, "inset").removeSuffix("%").toFloat(),
          0.001f,
      )
      assertEquals("bitmap", inset.nextStartTag().name)
      assertEquals(
          Gravity.FILL,
          Integer.decode(inset.getAttributeValue(ANDROID_NAMESPACE, "gravity")),
      )
      assertEquals(
          R.drawable.yarumo_app_icon_mark,
          inset.resourceAttribute("src"),
      )
    }
  }

  @Test
  fun `approved source bytes and monochrome resources remain resolvable`() {
    val sourceHash =
        context.resources.openRawResource(R.drawable.yarumo_app_icon_mark).use { input ->
          MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString(
              separator = ""
          ) { byte ->
            "%02x".format(byte)
          }
        }

    assertEquals(
        "0eabd16bbfc8ba3f1edaa14ad25702f5beb0131eb71cb63e3085b2ee230b2431",
        sourceHash,
    )
    assertTrue(
        context.resources.getDrawable(R.drawable.ic_launcher_monochrome, null) is VectorDrawable,
    )
    assertTrue(
        context.resources.getDrawable(R.drawable.ic_notification_gym, null) is VectorDrawable,
    )
  }

  @Test
  fun `brand resources resolve in day and night configurations`() {
    listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES).forEach { nightMode ->
      val configuration = Configuration(context.resources.configuration)
      configuration.uiMode =
          (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
      val themedContext = context.createConfigurationContext(configuration)

      assertEquals("Yarumo coach", themedContext.getString(R.string.app_name))
      assertTrue(
          themedContext.resources.getDrawable(R.mipmap.ic_launcher, null) is AdaptiveIconDrawable,
      )
    }
  }

  @Test
  fun `monochrome lower lobe remains inside the outer mark`() {
    val lowerLobe =
        monochromePathData().single { pathData -> pathData.startsWith("M512,376 a272,272") }
    val bounds = RectF()
    PathParser().parsePathString(lowerLobe).toPath().asAndroidPath().computeBounds(bounds, true)

    assertTrue(bounds.top >= OUTER_CIRCLE_TOP)
    assertTrue(bounds.bottom + INNER_STROKE_HALF <= OUTER_CIRCLE_BOTTOM + OUTER_STROKE_HALF)
  }

  private fun android.content.res.XmlResourceParser.atStartTag():
      android.content.res.XmlResourceParser {
    while (eventType != XmlPullParser.START_TAG) next()
    return this
  }

  private fun android.content.res.XmlResourceParser.nextStartTag():
      android.content.res.XmlResourceParser {
    do next() while (eventType != XmlPullParser.START_TAG)
    return this
  }

  private fun android.content.res.XmlResourceParser.resourceAttribute(name: String): Int {
    val index = (0 until attributeCount).first { getAttributeName(it) == name }
    return getAttributeResourceValue(index, 0)
  }

  private fun android.content.res.XmlResourceParser.adaptiveLayers(): Map<String, Int> {
    val adaptiveDepth = depth
    return buildMap {
      while (next() != XmlPullParser.END_DOCUMENT) {
        if (eventType == XmlPullParser.END_TAG && depth == adaptiveDepth) break
        if (eventType == XmlPullParser.START_TAG) put(name, resourceAttribute("drawable"))
      }
    }
  }

  private fun monochromePathData(): List<String> {
    val vector = context.resources.getXml(R.drawable.ic_launcher_monochrome).atStartTag()
    return buildList {
      while (vector.next() != XmlPullParser.END_DOCUMENT) {
        if (vector.eventType == XmlPullParser.START_TAG && vector.name == "path") {
          add(vector.getAttributeValue(ANDROID_NAMESPACE, "pathData"))
        }
      }
    }
  }

  private companion object {
    const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    const val OUTER_CIRCLE_TOP = 104f
    const val OUTER_CIRCLE_BOTTOM = 920f
    const val OUTER_STROKE_HALF = 14f
    const val INNER_STROKE_HALF = 12f
  }
}
