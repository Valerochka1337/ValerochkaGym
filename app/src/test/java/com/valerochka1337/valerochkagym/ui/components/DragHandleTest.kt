package com.valerochka1337.valerochkagym.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DragHandleTest {
  @get:Rule val composeRule = createComposeRule()

  private var starts = 0
  private var stops = 0
  private var longPressTimeout = 0L

  @Test
  fun `tapping the handle does not start dragging`() {
    showHandle()

    composeRule.onNodeWithTag("handle-1").performTouchInput { click() }

    composeRule.runOnIdle {
      assertEquals(0, starts)
      assertEquals(0, stops)
    }
  }

  @Test
  fun `moving the finger before holding does not start dragging`() {
    showHandle()

    composeRule.onNodeWithTag("handle-1").performTouchInput {
      swipe(center, center + Offset(0f, 100f), durationMillis = 100)
    }

    composeRule.runOnIdle {
      assertEquals(0, starts)
      assertEquals(0, stops)
    }
  }

  @Test
  fun `holding the handle starts dragging and releasing stops it`() {
    showHandle()
    val handle = composeRule.onNodeWithTag("handle-1")

    handle.performTouchInput {
      down(center)
      advanceEventTime(longPressTimeout + 100)
      moveBy(Offset(0f, 20f))
    }
    composeRule.runOnIdle {
      assertEquals(1, starts)
      assertEquals(0, stops)
    }

    handle.performTouchInput { up() }
    composeRule.runOnIdle {
      assertEquals(1, starts)
      assertEquals(1, stops)
    }
  }

  private fun showHandle() {
    composeRule.setContent {
      GymTheme {
        longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
        val listState = rememberLazyListState()
        val reorderState = rememberReorderableLazyListState(listState) { _, _ -> }
        LazyColumn(state = listState, modifier = Modifier.height(240.dp)) {
          items(listOf(1, 2, 3), key = { it }) { id ->
            ReorderableItem(reorderState, key = id) {
              Box(Modifier.testTag("handle-$id")) {
                DragHandle(
                    reorderableItemScope = this@ReorderableItem,
                    onDragStarted = { starts++ },
                    onDragStopped = { stops++ },
                )
              }
            }
          }
        }
      }
    }
    composeRule.waitForIdle()
  }
}
