package com.valerochka1337.valerochkagym.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.ui.coach.*
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

abstract class CoachChatSemanticsBase {
  @get:Rule val compose = createComposeRule()

  private fun content(
      initial: CoachChatUiState,
      applied: (String) -> Unit = {},
      sent: (String) -> Unit = {},
      canceled: (String) -> Unit = {},
  ) {
    val state = mutableStateOf(initial)
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
        GymTheme {
          CoachChatContent(
              state = state.value,
              onBack = {},
              onDraftChange = { state.value = state.value.copy(draft = it) },
              onSend = sent,
              onConfirm = { id ->
                applied(id)
                state.value = state.value.copy(busy = true, status = "Применяем изменения…")
              },
              onCancel = canceled,
              onUndo = {},
              onDisableInitiative = { state.value = state.value.copy(initiativeEnabled = false) },
          )
        }
      }
    }
  }

  @Test
  fun `read only history exposes transcript and no executable controls`() {
    content(
        CoachChatUiState(
            messages =
                listOf(CoachChatMessage("remote", "assistant", "Импортированное предложение")),
            proposal = CoachChatProposal("untrusted", "Было", "Станет"),
            readOnly = true,
            canUndo = true,
        )
    )
    compose.onNodeWithText("Импортированное предложение", substring = true).assertExists()
    compose.onNodeWithTag("coach-read-only").assertExists()
    listOf("coach-input", "coach-send", "coach-apply", "coach-cancel", "coach-undo", "coach-mute")
        .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
  }

  @Test
  fun `proposal exposes full comparison and disables a repeated confirmation`() {
    val applied = mutableListOf<String>()
    content(
        CoachChatUiState(
            proposal =
                CoachChatProposal(
                    "proposal-id",
                    "Жим, подход 3: 60 кг × 8",
                    "Жим, подход 3: 55 кг × 8",
                )
        ),
        applied::add,
    )
    compose.onNodeWithText("Было").assertExists()
    compose.onNodeWithText("Станет").assertExists()
    compose.onNodeWithTag("coach-conversation").performScrollToNode(hasTestTag("coach-apply"))
    compose
        .onNodeWithTag("coach-apply")
        .performScrollTo()
        .assertIsDisplayed()
        .assertHeightIsAtLeast(48.dp)
        .assertWidthIsAtLeast(48.dp)
        .performClick()
    assertEquals(listOf("proposal-id"), applied)
    compose.onNodeWithTag("coach-apply").assertIsNotEnabled()
    compose
        .onNodeWithTag("coach-cancel")
        .assertIsNotEnabled()
        .assertHeightIsAtLeast(48.dp)
        .assertWidthIsAtLeast(48.dp)
  }

  @Test
  fun `structured proposal displays exact changes and applies the entire packet`() {
    val applied = mutableListOf<String>()
    content(
        CoachChatUiState(
            proposal =
                CoachChatProposal(
                    "structured",
                    "legacy before",
                    "legacy after",
                    com.valerochka1337.valerochkagym.domain.WorkoutApprovalPreview(
                        listOf(
                            com.valerochka1337.valerochkagym.domain.ApprovalAction(
                                "edit",
                                "Жим · подход 3",
                                listOf("Вес: 60 кг → 55 кг"),
                            ),
                            com.valerochka1337.valerochkagym.domain.ApprovalAction(
                                "move",
                                "Переместить тягу в начало тренировки",
                            ),
                        )
                    ),
                )
        ),
        applied = applied::add,
    )
    compose.onNodeWithTag("coach-conversation").performScrollToNode(hasTestTag("coach-proposal"))
    compose.onNodeWithText("Жим · подход 3").assertExists()
    compose.onNodeWithText("Вес: 60 кг → 55 кг").assertExists()
    compose.onNodeWithText("legacy before").assertDoesNotExist()
    compose.onNodeWithTag("coach-apply").performScrollTo().assertIsEnabled().performClick()
    assertEquals(listOf("structured"), applied)
    compose.onNodeWithTag("coach-apply").assertIsNotEnabled()
  }

  @Test
  fun `empty structured proposal disables apply and still allows rejecting the packet`() {
    val canceled = mutableListOf<String>()
    content(
        CoachChatUiState(
            proposal =
                CoachChatProposal(
                    "empty",
                    "",
                    "",
                    com.valerochka1337.valerochkagym.domain.WorkoutApprovalPreview(emptyList()),
                )
        ),
        canceled = canceled::add,
    )
    compose.onNodeWithTag("coach-conversation").performScrollToNode(hasTestTag("coach-proposal"))
    compose.onNodeWithTag("coach-apply").performScrollTo().assertIsNotEnabled()
    compose.onNodeWithTag("coach-cancel").performScrollTo().performClick()
    assertEquals(listOf("empty"), canceled)
  }

  @Test
  fun `busy and failed states remain readable and suppress repeated send`() {
    content(
        CoachChatUiState(
            draft = "Добавь подход",
            busy = true,
            status = "Тренер отвечает…",
            error = "Нет подключения",
        )
    )
    compose.onNodeWithTag("coach-status").assertExists()
    compose.onNodeWithTag("coach-error").assertExists()
    compose.onNodeWithTag("coach-conversation").performScrollToNode(hasTestTag("coach-send"))
    compose.onNodeWithTag("coach-send").performScrollTo().assertIsNotEnabled()
  }

  @Test
  fun `quick phrase sends immediately and preserves editable input at font scale two`() {
    val sent = mutableListOf<String>()
    content(CoachChatUiState(draft = "Мой черновик"), sent = sent::add)
    compose.onNodeWithTag("coach-conversation").performScrollToNode(hasText("Добавь подход"))
    compose
        .onNodeWithText("Добавь подход")
        .performScrollTo()
        .assertHeightIsAtLeast(48.dp)
        .performClick()
    assertEquals(listOf("Добавь подход"), sent)
    compose.onNodeWithTag("coach-input").performScrollTo().assertTextContains("Мой черновик")
    compose.onNodeWithText("Осталось 20 минут").assertDoesNotExist()
    compose.onNodeWithText("Увеличь отдых").assertDoesNotExist()
  }

  @Test
  fun `contextual reply sends model text without bringing back initial phrases`() {
    val sent = mutableListOf<String>()
    content(
        CoachChatUiState(
            messages =
                listOf(
                    CoachChatMessage("u", "user", "Нужна замена"),
                    CoachChatMessage(
                        "a",
                        "assistant",
                        "Заменить жим на отжимания?",
                        quickReplies = listOf("Да, замени на отжимания", "Оставим жим"),
                    ),
                )
        ),
        sent = sent::add,
    )
    compose.onNodeWithTag("coach-conversation").performScrollToNode(hasText("Оставим жим"))
    compose.onNodeWithText("Оставим жим").performScrollTo().performClick()
    assertEquals(listOf("Оставим жим"), sent)
    compose.onNodeWithText("Тренажёр занят").assertDoesNotExist()
    compose.onNodeWithText("Добавь подход").assertDoesNotExist()
  }

  @Test
  fun `busy contextual replies are disabled`() {
    content(
        CoachChatUiState(
            busy = true,
            messages =
                listOf(
                    CoachChatMessage(
                        "a",
                        "assistant",
                        "Заменить?",
                        quickReplies = listOf("Да, замени"),
                    ),
                ),
        )
    )
    compose.onNodeWithTag("coach-conversation").performScrollToNode(hasText("Да, замени"))
    compose.onNodeWithText("Да, замени").assertIsNotEnabled()
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class CoachChatContentTest : CoachChatSemanticsBase()

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w1200dp-h900dp-xhdpi")
class ExpandedCoachChatContentTest : CoachChatSemanticsBase()

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w720dp-h900dp-xhdpi")
class MediumCoachChatContentTest : CoachChatSemanticsBase()
