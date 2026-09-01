package com.valerochka1337.valerochkagym.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val CONTENT_TAG = "adaptive-content"

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class CompactNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `compact window reserves the bottom edge for navigation`() {
        composeRule.setAdaptiveNavigationContent()

        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val content = composeRule.onNodeWithTag(CONTENT_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(content.left == root.left)
        assertTrue(content.bottom < root.bottom)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w1200dp-h800dp-xhdpi")
class ExpandedNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `expanded window reserves the start edge for navigation rail`() {
        composeRule.setAdaptiveNavigationContent()

        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val content = composeRule.onNodeWithTag(CONTENT_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(content.left > root.left)
        assertTrue(content.bottom == root.bottom)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.setAdaptiveNavigationContent() {
    setContent {
        GymTheme {
            NavigationSuiteScaffold(
                navigationSuiteItems = {
                    item(
                        selected = true,
                        onClick = {},
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.FitnessCenter,
                                contentDescription = "Тренировки",
                            )
                        },
                        label = { Text("Тренировки") },
                    )
                },
            ) {
                Box(modifier = Modifier.fillMaxSize().testTag(CONTENT_TAG))
            }
        }
    }
}
