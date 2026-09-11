package com.valerochka1337.valerochkagym.ui

import com.valerochka1337.valerochkagym.ui.coach.*
import org.junit.Assert.*
import org.junit.Test

class CoachQuickRepliesTest {
  @Test
  fun `local welcome retains exactly the three initial phrases`() {
    val state =
        CoachChatUiState(messages = listOf(CoachChatMessage("welcome", "assistant", "Привет")))
    assertEquals(listOf("Тренажёр занят", "Слишком тяжело", "Добавь подход"), state.quickReplies)
  }

  @Test
  fun `empty model options never fall back to initial phrases`() {
    val state =
        CoachChatUiState(
            messages =
                listOf(CoachChatMessage("a", "assistant", "Ответ", quickReplies = emptyList()))
        )
    assertTrue(state.quickReplies.isEmpty())
  }

  @Test
  fun `system action invalidates older contextual options`() {
    val state =
        CoachChatUiState(
            messages =
                listOf(
                    CoachChatMessage("a", "assistant", "Вопрос", quickReplies = listOf("Да")),
                    CoachChatMessage("s", "system", "Изменение применено"),
                )
        )
    assertTrue(state.quickReplies.isEmpty())
  }
}
