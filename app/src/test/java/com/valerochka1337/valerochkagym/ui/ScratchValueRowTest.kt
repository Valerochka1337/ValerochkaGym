package com.valerochka1337.valerochkagym.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.valerochka1337.valerochkagym.ui.analysis.ValueRow
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
class ScratchValueRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `long value row bounds`() {
        val label = "Больше всего дают"
        val value = listOf(
            "Жим штанги на наклонной скамье",
            "Сведение рук в кроссовере",
            "Жим гантелей лёжа",
        ).joinToString(", ")

        composeRule.setContent {
            GymTheme {
                Column(modifier = Modifier.width(324.dp)) {
                    ValueRow(label = label, value = value)
                }
            }
        }
        composeRule.waitForIdle()

        val labelBounds = composeRule.onNodeWithText(label).getUnclippedBoundsInRoot()
        val valueBounds = composeRule.onNodeWithText(value).getUnclippedBoundsInRoot()
        val rootBounds = composeRule.onRoot().getUnclippedBoundsInRoot()
        println("SCRATCH label bounds = $labelBounds  w=${labelBounds.width} h=${labelBounds.height}")
        println("SCRATCH value bounds = $valueBounds  w=${valueBounds.width} h=${valueBounds.height}")
        println("SCRATCH root  bounds = $rootBounds  w=${rootBounds.width} h=${rootBounds.height}")
    }

    @Test
    fun `two exercises at 292dp`() {
        val label = "Больше всего дают"
        val value = "Жим штанги на наклонной скамье, Сведение рук в кроссовере"
        composeRule.setContent {
            GymTheme {
                Column(modifier = Modifier.width(292.dp)) {
                    ValueRow(label = label, value = value)
                }
            }
        }
        composeRule.waitForIdle()
        val lb = composeRule.onNodeWithText(label).getUnclippedBoundsInRoot()
        println("SCRATCH two-name label w=${lb.width} h=${lb.height}")
    }

    @Test
    fun `one long exercise at 292dp`() {
        val label = "Больше всего дают"
        val value = "Жим гантелей на наклонной скамье"
        composeRule.setContent {
            GymTheme {
                Column(modifier = Modifier.width(292.dp)) {
                    ValueRow(label = label, value = value)
                }
            }
        }
        composeRule.waitForIdle()
        val lb = composeRule.onNodeWithText(label).getUnclippedBoundsInRoot()
        val vb = composeRule.onNodeWithText(value).getUnclippedBoundsInRoot()
        println("SCRATCH one-name label w=${lb.width} h=${lb.height} value w=${vb.width}")
    }

    @Test
    fun `short value row bounds for comparison`() {
        val label = "Подходов в неделю"
        val value = "12,5"

        composeRule.setContent {
            GymTheme {
                Column(modifier = Modifier.width(324.dp)) {
                    ValueRow(label = label, value = value)
                }
            }
        }
        composeRule.waitForIdle()

        val labelBounds = composeRule.onNodeWithText(label).getUnclippedBoundsInRoot()
        println("SCRATCH short label bounds w=${labelBounds.width} h=${labelBounds.height}")
        println("SCRATCH short root h=${composeRule.onRoot().getUnclippedBoundsInRoot().height}")
    }
}
