package com.valerochka1337.valerochkagym.ui.coach

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CoachTextSmootherTest {
  @Test
  fun `suffix appears gradually and catches up within two hundred milliseconds`() {
    val smoother = CoachTextSmoother()
    val text = "abcdefghijklmnopqrstuvwx".repeat(5)
    assertEquals("", smoother.update(text, 0))
    val first = smoother.update(text, 32)
    assertTrue(first.isNotEmpty() && first.length < text.length)
    assertEquals(text, smoother.update(text, 192))
  }

  @Test
  fun `continuous arrivals do not reset earlier deadlines`() {
    val smoother = CoachTextSmoother()
    var text = ""
    for (time in 0L..384L step 32) {
      text += "💪🏽"
      val visible = smoother.update(text, time)
      assertEquals(0, visible.length % 4)
      if (time >= 192) assertTrue(visible.length >= 4)
    }
    assertEquals(text, smoother.update(text, 576))
  }

  @Test
  fun `reopening disabled motion and replacement show accumulated text immediately`() {
    val smoother = CoachTextSmoother("Сохранено")
    assertEquals("Сохранено", smoother.visible)
    assertEquals("Сохранено целиком", smoother.update("Сохранено целиком", 0, immediate = true))
    assertEquals("Ошибка", smoother.update("Ошибка", 1))
  }
}
