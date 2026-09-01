package com.valerochka1337.valerochkagym.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.ui.analysis.charts.LinePoint
import com.valerochka1337.valerochkagym.ui.analysis.charts.TrendLineChart
import com.valerochka1337.valerochkagym.ui.components.CircleIconButton
import com.valerochka1337.valerochkagym.ui.components.GymTopBar
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w420dp-h800dp-xhdpi")
class AccessibilityFoundationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `buttons keep forty eight dp targets at two hundred percent text`() {
        var minimumTargetPx = 0f

        composeRule.setContent {
            val density = LocalDensity.current
            minimumTargetPx = with(density) { 48.dp.toPx() }
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                GymTheme {
                    Column(modifier = Modifier.width(240.dp)) {
                        PillButton(text = "Компактная", onClick = {}, compact = true)
                        CircleIconButton(
                            icon = Icons.Rounded.Settings,
                            contentDescription = "Настройки",
                            onClick = {},
                        )
                    }
                }
            }
        }

        val button = composeRule.onNodeWithText("Компактная").fetchSemanticsNode().boundsInRoot
        val iconButton = composeRule.onNodeWithContentDescription("Настройки")
            .fetchSemanticsNode()
            .boundsInRoot

        assertTrue(button.width >= minimumTargetPx && button.height >= minimumTargetPx)
        assertTrue(iconButton.width >= minimumTargetPx && iconButton.height >= minimumTargetPx)
    }

    @Test
    fun `trend points are selectable through accessibility actions`() {
        var selectedIndex: Int? = null
        composeRule.setContent {
            GymTheme {
                TrendLineChart(
                    points = listOf(
                        LinePoint(1L, 10f, "первая"),
                        LinePoint(2L, 20f, "вторая"),
                    ),
                    onSelect = { selectedIndex = it },
                )
            }
        }

        val actions = composeRule
            .onNodeWithContentDescription("График тренда, 2 точек")
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        assertEquals(2, actions.size)

        composeRule.runOnIdle { actions[1].action() }
        assertEquals(1, selectedIndex)
    }

    @Test
    fun `top bar actions keep an eight dp gap`() {
        var minimumGapPx = 0f
        composeRule.setContent {
            minimumGapPx = with(LocalDensity.current) { 8.dp.toPx() }
            GymTheme {
                GymTopBar(
                    title = "Тренировки",
                    onOpenSettings = {},
                    actions = {
                        CircleIconButton(
                            icon = Icons.Rounded.Add,
                            contentDescription = "Новая программа",
                            onClick = {},
                        )
                    },
                )
            }
        }

        val add = composeRule.onNodeWithContentDescription("Новая программа")
            .fetchSemanticsNode()
            .boundsInRoot
        val settings = composeRule.onNodeWithContentDescription("Настройки")
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(settings.left - add.right >= minimumGapPx)
    }
}
