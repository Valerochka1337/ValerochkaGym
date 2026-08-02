package com.valerochka1337.valerochkagym.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.valerochka1337.valerochkagym.ui.analysis.ValueBlock
import com.valerochka1337.valerochkagym.ui.analysis.ValueRow
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Проверки раскладки строк «подпись — значение».
 *
 * Без веса у значения строка ломалась молча: значение мерялось первым и по всей ширине, подписи
 * оставалось ноль, и она выводилась в столбик по одной букве. Компилятор такое не ловит, поэтому
 * ширина подписи проверяется числом.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w420dp-h4000dp-xhdpi")
class ValueRowLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Ширина содержимого карточки аналитики на телефоне — там строка и живёт. */
    private val cardWidth = 324.dp

    @Test
    fun `a long value never squeezes the label`() {
        val label = "Ориентир (MEV · коридор · MRV)"
        val value = "Жим штанги на наклонной скамье, Сведение рук в кроссовере"

        composeRule.setContent {
            GymTheme {
                Column(modifier = Modifier.width(cardWidth)) {
                    ValueRow(label = label, value = value)
                }
            }
        }
        composeRule.waitForIdle()

        val labelBounds = composeRule.onNodeWithText(label).getUnclippedBoundsInRoot()
        assertTrue(
            "подпись получила ${labelBounds.width} — значение забрало строку себе",
            labelBounds.width > cardWidth / 2,
        )
        assertTrue("подпись рассыпалась в столбик: ${labelBounds.height}", labelBounds.height < 60.dp)
    }

    @Test
    fun `a short value keeps to the right edge`() {
        val label = "Подходов в неделю"
        val value = "12,5"

        composeRule.setContent {
            GymTheme {
                Column(modifier = Modifier.width(cardWidth)) {
                    ValueRow(label = label, value = value)
                }
            }
        }
        composeRule.waitForIdle()

        val valueBounds = composeRule.onNodeWithText(value).getUnclippedBoundsInRoot()
        assertTrue(
            "значение оторвалось от правого края: ${valueBounds.right} из $cardWidth",
            valueBounds.right > cardWidth - 4.dp,
        )
    }

    @Test
    fun `a block puts a long value on its own lines`() {
        val label = "Больше всего дают"
        val value = "Жим штанги лёжа, Бёрпи, Сведение рук в кроссовере"

        composeRule.setContent {
            GymTheme {
                Column(modifier = Modifier.width(cardWidth)) {
                    ValueBlock(label = label, value = value)
                }
            }
        }
        composeRule.waitForIdle()

        val labelBounds = composeRule.onNodeWithText(label).getUnclippedBoundsInRoot()
        val valueBounds = composeRule.onNodeWithText(value).getUnclippedBoundsInRoot()
        assertTrue("значение должно идти под подписью", valueBounds.top >= labelBounds.bottom)
        assertTrue("значение должно переноситься, а не обрезаться", valueBounds.height > 20.dp)
        assertTrue("значение не должно вылезать за карточку", valueBounds.width <= cardWidth)
    }
}
