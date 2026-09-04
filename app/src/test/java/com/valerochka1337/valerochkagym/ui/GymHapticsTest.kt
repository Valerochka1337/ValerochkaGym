package com.valerochka1337.valerochkagym.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.valerochka1337.valerochkagym.ui.haptics.GymHaptics
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.haptics.rememberGymHaptics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Unit tests for [GymHaptics]: карта «семантика → HapticFeedbackType» и no-op при выключенной
 * настройке. Системная хаптика подменяется фейком через [LocalHapticFeedback].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GymHapticsTest {

  @get:Rule val compose = createComposeRule()

  private class RecordingHaptics : HapticFeedback {
    val performed = mutableListOf<HapticFeedbackType>()

    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
      performed += hapticFeedbackType
    }
  }

  private fun withHaptics(enabled: Boolean, block: (GymHaptics) -> Unit): RecordingHaptics {
    val recorder = RecordingHaptics()
    compose.setContent {
      CompositionLocalProvider(LocalHapticFeedback provides recorder) {
        val haptics = rememberGymHaptics(enabled = enabled)
        block(haptics)
        Text("ok")
      }
    }
    compose.waitForIdle()
    return recorder
  }

  @Test
  fun `semantic events map to the expected feedback types`() {
    val recorder =
        withHaptics(enabled = true) { haptics ->
          haptics.tap()
          haptics.confirm()
          haptics.success()
          haptics.reject()
          haptics.toggle(on = true)
          haptics.toggle(on = false)
          haptics.step()
          haptics.stepFrequent()
          haptics.dragStart()
          haptics.dragEnd()
          haptics.longPress()
        }

    assertEquals(
        listOf(
            HapticFeedbackType.ContextClick,
            HapticFeedbackType.Confirm,
            HapticFeedbackType.Confirm,
            HapticFeedbackType.Reject,
            HapticFeedbackType.ToggleOn,
            HapticFeedbackType.ToggleOff,
            HapticFeedbackType.SegmentTick,
            HapticFeedbackType.SegmentFrequentTick,
            HapticFeedbackType.GestureThresholdActivate,
            HapticFeedbackType.GestureEnd,
            HapticFeedbackType.LongPress,
        ),
        recorder.performed,
    )
  }

  @Test
  fun `disabled haptics perform nothing`() {
    val recorder =
        withHaptics(enabled = false) { haptics ->
          haptics.tap()
          haptics.confirm()
          haptics.step()
          haptics.dragStart()
          haptics.dragEnd()
        }

    assertTrue(recorder.performed.isEmpty())
  }

  @Test
  fun `screens get a no-op stub when the root provided nothing`() {
    // gymHaptics() без LocalGymHaptics не падает и ничего не отбивает.
    val recorder = RecordingHaptics()
    compose.setContent {
      CompositionLocalProvider(LocalHapticFeedback provides recorder) {
        gymHaptics().tap()
        Text("ok")
      }
    }
    compose.waitForIdle()

    assertTrue(recorder.performed.isEmpty())
  }
}
