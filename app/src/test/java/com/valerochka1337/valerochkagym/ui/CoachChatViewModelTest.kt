package com.valerochka1337.valerochkagym.ui

import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.ai.AiApiChatResponse
import com.valerochka1337.valerochkagym.data.ai.AiApiMessage
import com.valerochka1337.valerochkagym.data.ai.AiApiTool
import com.valerochka1337.valerochkagym.data.ai.CoachAgent
import com.valerochka1337.valerochkagym.data.ai.CoachModelGateway
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import com.valerochka1337.valerochkagym.data.db.entity.CoachProposalEntity
import com.valerochka1337.valerochkagym.data.db.entity.CoachSessionContextEntity
import com.valerochka1337.valerochkagym.domain.CoachWorkoutReader
import com.valerochka1337.valerochkagym.domain.WorkoutChangeSet
import com.valerochka1337.valerochkagym.domain.WorkoutEditor
import com.valerochka1337.valerochkagym.domain.WorkoutWriteQueue
import com.valerochka1337.valerochkagym.service.CoachConversationService
import com.valerochka1337.valerochkagym.service.RestTimerEngine
import com.valerochka1337.valerochkagym.service.WallClock
import com.valerochka1337.valerochkagym.ui.coach.CoachChatViewModel
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CoachChatViewModelTest : RoomDaoTest() {
  @get:Rule val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

  @Test
  fun `finished workout changes an open chat to read only`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val workoutId = insertWorkout("finished")
        val viewModel = viewModel(workoutId)
        assertFalse(viewModel.uiState.first { !it.readOnly }.readOnly)

        db.workoutDao().setFinishedAt(workoutId, 123L)
        assertTrue(viewModel.uiState.first { it.readOnly }.readOnly)
      }

  @Test
  fun `missing workout route stays read only before a foreground service can start`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val viewModel = viewModel("missing")

        assertTrue(viewModel.uiState.first { it.readOnly }.readOnly)
      }

  @Test
  fun `pending proposal and undo state restore from Room`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val workoutId = insertWorkout("proposal")
        val packet =
            WorkoutChangeSet.Packet(
                listOf(WorkoutChangeSet.Operation.SetAvailableTime(20))
            )
        db.coachDao()
            .saveContext(CoachSessionContextEntity(workoutId, "user", lastUndoRevision = 4L))
        db.coachDao()
            .saveProposal(
                CoachProposalEntity(
                    id = "proposal",
                    accountId = "user",
                    workoutId = workoutId,
                    baseRevision = 0,
                    beforeSummary = "Было",
                    afterSummary = "Станет",
                    packetJson = Json.encodeToString(WorkoutChangeSet.Packet.serializer(), packet),
                    expiresAt = Long.MAX_VALUE,
                )
            )
        val viewModel = viewModel(workoutId)
        val state = viewModel.uiState.first { it.proposal != null && it.canUndo }
        assertEquals("proposal", state.proposal?.id)
        assertEquals("Было", state.proposal?.before)
        assertTrue(state.canUndo)
      }

  @Test
  fun `double confirmation accepts only one action while the first is busy`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val workoutId = insertWorkout("confirm")
        db.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO backend_state (id, owner, generation, phase, initialMergeAcknowledged) VALUES (1, 'user', 0, 'OWNED', 1)",
        )
        val packet =
            WorkoutChangeSet.Packet(
                listOf(WorkoutChangeSet.Operation.SetAvailableTime(20))
            )
        db.coachDao()
            .saveProposal(
                CoachProposalEntity(
                    id = "proposal",
                    accountId = "user",
                    workoutId = workoutId,
                    baseRevision = 0,
                    beforeSummary = "Было",
                    afterSummary = "Станет",
                    packetJson = Json.encodeToString(WorkoutChangeSet.Packet.serializer(), packet),
                    expiresAt = Long.MAX_VALUE,
                )
            )
        val viewModel = viewModel(workoutId)
        viewModel.uiState.first { it.proposal?.id == "proposal" }

        viewModel.confirm("proposal")
        viewModel.confirm("proposal")
        db.coachDao().observePendingProposal(workoutId).first { it == null }
        assertEquals(
            "CONFIRMED",
            db.coachDao().pendingProposalForId("proposal")?.let { "PENDING" } ?: "CONFIRMED",
        )
        assertEquals(1, db.coachDao().messages(workoutId).count { it.role == "system" })
      }

  private fun TestScope.viewModel(workoutId: String): CoachChatViewModel =
      CoachChatViewModel(
          SavedStateHandle(mapOf(GymRoutes.WORKOUT_ID_ARG to workoutId)),
          db.coachDao(),
          db.workoutDao(),
          conversation(),
      )

  private fun TestScope.conversation(): CoachConversationService {
    val timer = RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime })
    val coordinator =
        WorkoutEditor(
            db,
            db.workoutDao(),
            db.coachDao(),
            timer,
            session,
            WorkoutWriteQueue(),
        )
    val control = CoachWorkoutReader(db, timer, session)
    return CoachConversationService(CoachAgent(NoopGateway), control, coordinator, db, session)
  }

  private val session = FakeSession()

  private class FakeSession : BackendSessionStore {
    private val state =
        MutableStateFlow<BackendTokens?>(
            BackendTokens("user", "user@example.com", "access", "refresh")
        )
    override val session: StateFlow<BackendTokens?> = state

    override fun save(tokens: BackendTokens?) {
      state.value = tokens
    }
  }

  private object NoopGateway : CoachModelGateway {
    override suspend fun complete(
        expectedOwner: String,
        expectedSessionEpoch: Long?,
        messages: List<AiApiMessage>,
        tools: List<AiApiTool>,
    ) = AiApiChatResponse()
  }
}
