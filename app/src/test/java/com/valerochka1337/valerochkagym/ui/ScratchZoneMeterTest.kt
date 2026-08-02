package com.valerochka1337.valerochkagym.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.valerochka1337.valerochkagym.domain.analysis.WorkloadRatio
import com.valerochka1337.valerochkagym.ui.analysis.WorkloadCard
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w420dp-h4000dp-xhdpi")
class ScratchZoneMeterTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `boundary label positions vs real zone boundaries`() {
        composeRule.setContent {
            GymTheme {
                Column(modifier = Modifier.width(360.dp).padding(16.dp)) {
                    WorkloadCard(
                        workload = WorkloadRatio(
                            acuteSets = 30.0,
                            chronicWeeklySets = 30.0,
                            ratio = 1.0,
                            hasEnoughData = true,
                        ),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        println("SCRATCH root width = ${root.width}")

        // Ширина дорожки = ширина контента карточки. Возьмём её из строки подписей:
        // первая подпись начинается на левом краю дорожки, последняя кончается на правом.
        val zero = composeRule.onNodeWithText("0").getUnclippedBoundsInRoot()
        val l08 = composeRule.onNodeWithText("0.8").getUnclippedBoundsInRoot()
        val l13 = composeRule.onNodeWithText("1.3").getUnclippedBoundsInRoot()
        val l15 = composeRule.onNodeWithText("1.5").getUnclippedBoundsInRoot()
        val l2 = composeRule.onNodeWithText("2+").getUnclippedBoundsInRoot()

        val trackLeft = zero.left
        val trackRight = l2.right
        val trackWidth = trackRight - trackLeft
        println("SCRATCH track left=$trackLeft right=$trackRight width=$trackWidth")

        fun center(name: String, b: androidx.compose.ui.unit.DpRect, value: Float) {
            val labelCenter = (b.left + b.right) / 2f - trackLeft
            val labelPct = labelCenter / trackWidth * 100f
            val truePct = value / 2f * 100f
            println(
                "SCRATCH $name labelCenter=${labelCenter} (${labelPct}%) " +
                    "trueX=${trackWidth * value / 2f} (${truePct}%) " +
                    "delta=${labelCenter - trackWidth * value / 2f}",
            )
        }
        center("0", zero, 0f)
        center("0.8", l08, 0.8f)
        center("1.3", l13, 1.3f)
        center("1.5", l15, 1.5f)
        center("2+", l2, 2f)

        val markerX = trackWidth * (1.0f / 2f)
        println("SCRATCH marker for ratio=1.0 at ${markerX} from track left")
        println("SCRATCH label '1.3' spans ${l13.left - trackLeft} .. ${l13.right - trackLeft}")
    }
}
