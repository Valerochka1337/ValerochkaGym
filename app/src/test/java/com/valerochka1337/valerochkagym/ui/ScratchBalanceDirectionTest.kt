package com.valerochka1337.valerochkagym.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.domain.analysis.BalanceId
import com.valerochka1337.valerochkagym.domain.analysis.BalanceRatio
import com.valerochka1337.valerochkagym.ui.analysis.BalanceCard
import com.valerochka1337.valerochkagym.ui.theme.ChartPalette
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w420dp-h2000dp-xhdpi")
class ScratchBalanceDirectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `push only paints bar on which side`() {
        val balances = listOf(
            BalanceRatio(BalanceId.PUSH_PULL, leftSets = 18.0, rightSets = 0.0, ratio = null, targetLow = 0.8, targetHigh = 1.25),
        )
        composeRule.setContent {
            GymTheme {
                Column(modifier = Modifier.width(420.dp).padding(16.dp)) {
                    BalanceCard(balances)
                }
            }
        }
        composeRule.waitForIdle()

        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val amber = ChartPalette.Maintenance.value.toLong().let {
            android.graphics.Color.argb(255, 0xF2, 0xA9, 0x3B)
        }
        var left = 0
        var right = 0
        val mid = bitmap.width / 2
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) == amber) {
                    if (x < mid) left++ else right++
                }
            }
        }
        println("AMBER-PIXELS left=$left right=$right width=${bitmap.width}")
        assertTrue("bar must lean to the RIGHT for push-heavy data", right > left)
    }
}
