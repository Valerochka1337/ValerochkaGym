package com.valerochka1337.valerochkagym.ui.trainingproposal

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import com.valerochka1337.valerochkagym.data.trainingproposal.ApprovalDraft
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalAuthor
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalPlannedExercise
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalPlannedSet
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalSnapshot
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalSource
import com.valerochka1337.valerochkagym.data.trainingproposal.ProposalStatus
import com.valerochka1337.valerochkagym.data.trainingproposal.TrainingProposal
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w840dp-h900dp-xhdpi")
class TrainingProposalComposeTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun `detail exposes original source version and distinct apply reject back callbacks`() {
    var applyCalls = 0
    var rejectCalls = 0
    var backCalls = 0
    compose.setContent {
      GymTheme {
        TrainingProposalDetailContent(
            proposal = proposal(),
            draft = draft(),
            saving = false,
            applied = false,
            error = null,
            exerciseChoices = listOf("bench" to "Жим лёжа"),
            gymChoices = listOf("gym" to "Дом"),
            onDraftChange = {},
            onApply = { applyCalls++ },
            onReject = { rejectCalls++ },
            onBack = { backCalls++ },
            onRetry = {},
        )
      }
    }

    compose.onNodeWithText("Оригинал").assertIsDisplayed()
    compose
        .onNodeWithText("Источник: Тренер · Автор: Тренер · версия 3 · Ожидает решения")
        .assertIsDisplayed()
    compose.onNodeWithContentDescription("Применить предложение").performScrollTo().performClick()
    compose.onNodeWithContentDescription("Отклонить предложение").performScrollTo().performClick()
    compose.onNodeWithText("Назад").performScrollTo().performClick()

    compose.runOnIdle {
      assertEquals(1, applyCalls)
      assertEquals(1, rejectCalls)
      assertEquals(1, backCalls)
    }
  }

  @Test
  fun `edited set weight reaches draft callback`() {
    var latestDraft = draft()
    compose.setContent {
      var displayedDraft by mutableStateOf(draft())
      GymTheme {
        TrainingProposalDetailContent(
            proposal = proposal(),
            draft = displayedDraft,
            saving = false,
            applied = false,
            error = null,
            exerciseChoices = listOf("bench" to "Жим лёжа"),
            gymChoices = emptyList(),
            onDraftChange = {
              latestDraft = it
              displayedDraft = it
            },
            onApply = {},
            onReject = {},
            onBack = {},
            onRetry = {},
        )
      }
    }

    compose.onNodeWithText("Вес подхода 1, кг").performScrollTo().performTextReplacement("70.5")

    compose.runOnIdle {
      assertEquals(70.5, latestDraft.exercises.first().plannedSets.first().weightKg)
    }
  }

  @Test
  fun `expanded font scale two keeps editable proposal actions reachable`() {
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme {
          TrainingProposalDetailContent(
              proposal = proposal(),
              draft = draft(),
              saving = false,
              applied = false,
              error = null,
              exerciseChoices = listOf("bench" to "Жим лёжа"),
              gymChoices = listOf("gym" to "Дом"),
              onDraftChange = {},
              onApply = {},
              onReject = {},
              onBack = {},
              onRetry = {},
          )
        }
      }
    }

    compose.onNodeWithText("Оригинал").assertIsDisplayed()
    compose
        .onNodeWithContentDescription("Применить предложение")
        .performScrollTo()
        .assertIsEnabled()
    compose
        .onNodeWithContentDescription("Отклонить предложение")
        .performScrollTo()
        .assertIsDisplayed()
  }

  @Test
  fun `approved proposal exposes result recovery without editing or rejection`() {
    var recoverCalls = 0
    compose.setContent {
      GymTheme {
        TrainingProposalDetailContent(
            proposal = proposal(status = ProposalStatus.APPROVED),
            draft = draft(),
            saving = false,
            applied = false,
            error = null,
            exerciseChoices = listOf("bench" to "Жим лёжа"),
            gymChoices = emptyList(),
            onDraftChange = {},
            onApply = { recoverCalls++ },
            onReject = {},
            onBack = {},
            onRetry = {},
        )
      }
    }

    compose.onNodeWithContentDescription("Загрузить результат").assertIsEnabled().performClick()
    compose.onNodeWithText("Этот вариант больше нельзя редактировать.").assertIsDisplayed()
    compose.runOnIdle { assertEquals(1, recoverCalls) }
  }

  @Test
  fun `inbox exposes loading error retry and proposal callback`() {
    var retries = 0
    var opened: String? = null
    compose.setContent {
      GymTheme {
        TrainingProposalInboxContent(
            items = listOf(proposal()),
            loading = true,
            error = "Не удалось обновить",
            hasMore = true,
            onRefresh = { retries++ },
            onMore = {},
            onOpen = { opened = it },
            onBack = {},
        )
      }
    }

    compose.onNodeWithContentDescription("Загружаем предложения").assertIsDisplayed()
    compose.onNodeWithText("Не удалось обновить").assertIsDisplayed()
    compose.onNodeWithText("Повторить").performClick()
    compose.onNodeWithText("Силовой план").performClick()
    compose.runOnIdle {
      assertEquals(1, retries)
      assertEquals("proposal-1", opened)
    }
  }

  @Test
  fun `detail keeps retry reachable when loading fails before a proposal arrives`() {
    var retries = 0
    compose.setContent {
      GymTheme {
        TrainingProposalDetailContent(
            proposal = null,
            draft = null,
            saving = false,
            applied = false,
            error = "Предложение не загружено",
            exerciseChoices = emptyList(),
            gymChoices = emptyList(),
            onDraftChange = {},
            onApply = {},
            onReject = {},
            onBack = {},
            onRetry = { retries++ },
        )
      }
    }

    compose.onNodeWithText("Предложение не загружено").assertIsDisplayed()
    compose.onNodeWithText("Повторить").performClick()
    compose.runOnIdle { assertEquals(1, retries) }
  }

  @Test
  fun `gym choice deselects instead of duplicating its id`() {
    var latestDraft = draft()
    compose.setContent {
      var displayedDraft by mutableStateOf(draft())
      GymTheme {
        TrainingProposalDetailContent(
            proposal = proposal(),
            draft = displayedDraft,
            saving = false,
            applied = false,
            error = null,
            exerciseChoices = listOf("bench" to "Жим лёжа"),
            gymChoices = listOf("gym" to "Дом"),
            onDraftChange = {
              latestDraft = it
              displayedDraft = it
            },
            onApply = {},
            onReject = {},
            onBack = {},
            onRetry = {},
        )
      }
    }

    compose.onNodeWithText("Дом").performClick()
    compose.runOnIdle { assertEquals(emptyList<String>(), latestDraft.gymIds) }
  }

  @Test
  fun `removing invalid set clears its transient error and restores apply`() {
    var latestDraft = twoSetDraft()
    compose.setContent {
      var displayedDraft by mutableStateOf(twoSetDraft())
      GymTheme {
        TrainingProposalDetailContent(
            proposal = proposal(),
            draft = displayedDraft,
            saving = false,
            applied = false,
            error = null,
            exerciseChoices = listOf("bench" to "Жим лёжа"),
            gymChoices = emptyList(),
            onDraftChange = {
              latestDraft = it
              displayedDraft = it
            },
            onApply = {},
            onReject = {},
            onBack = {},
            onRetry = {},
        )
      }
    }

    compose.onNodeWithText("Вес подхода 2, кг").performScrollTo().performTextReplacement("не число")
    compose.onAllNodesWithText("Удалить подход")[1].performScrollTo().performClick()

    compose
        .onNodeWithContentDescription("Применить предложение")
        .performScrollTo()
        .assertIsEnabled()
    compose.runOnIdle { assertEquals(1, latestDraft.exercises.first().plannedSets.size) }
  }
}

private fun draft() =
    ApprovalDraft(
        name = "Силовой план",
        gymIds = listOf("gym"),
        exercises =
            listOf(
                ProposalPlannedExercise(
                    exerciseId = "bench",
                    restSeconds = 90,
                    plannedSets = ProposalPlannedSet(60.0, 8, null, null, null).let(::listOf),
                )
            ),
        startsAtMillis = 1_800_000_000_000L,
        timeZoneId = "Europe/Moscow",
    )

private fun proposal(status: ProposalStatus = ProposalStatus.PENDING) =
    TrainingProposal(
        proposalId = "proposal-1",
        author = ProposalAuthor(ProposalSource.COACH, "coach-7"),
        recipientId = "recipient-1",
        source = ProposalSource.COACH,
        status = status,
        currentVersion = 3,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_100_000L,
        expiresAt = Long.MAX_VALUE,
        snapshot = ProposalSnapshot(3, draft(), 4L, 9L, 1_700_000_000_000L),
    )

private fun twoSetDraft() =
    draft()
        .copy(
            exercises =
                draft().exercises.map { exercise ->
                  exercise.copy(
                      plannedSets =
                          exercise.plannedSets + ProposalPlannedSet(65.0, 7, null, null, null)
                  )
                }
        )
