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
              onCancel = {},
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
  fun `quick phrase fills editable input before explicit send at font scale two`() {
    val sent = mutableListOf<String>()
    content(CoachChatUiState(), sent = sent::add)
    compose.onNodeWithTag("coach-conversation").performScrollToNode(hasText("Добавь подход"))
    compose.onNodeWithText("Добавь подход").performScrollTo().performClick()
    assertEquals(emptyList<String>(), sent)
    compose.onNodeWithTag("coach-input").performScrollTo().assertTextContains("Добавь подход")
    compose
        .onNodeWithTag("coach-send")
        .performScrollTo()
        .assertIsDisplayed()
        .assertIsEnabled()
        .assertHeightIsAtLeast(48.dp)
        .assertWidthIsAtLeast(48.dp)
        .performClick()
    assertEquals(listOf("Добавь подход"), sent)
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
