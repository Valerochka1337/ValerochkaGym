package com.valerochka1337.valerochkagym.service

import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.ai.AiApiChatResponse
import com.valerochka1337.valerochkagym.data.ai.AiApiChoice
import com.valerochka1337.valerochkagym.data.ai.AiApiMessage
import com.valerochka1337.valerochkagym.data.ai.AiApiResponseMessage
import com.valerochka1337.valerochkagym.data.ai.AiApiTool
import com.valerochka1337.valerochkagym.data.ai.CoachAgent
import com.valerochka1337.valerochkagym.data.ai.CoachModelGateway
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.domain.WorkoutControlService
import com.valerochka1337.valerochkagym.domain.WorkoutMutationCoordinator
import com.valerochka1337.valerochkagym.domain.WorkoutWriteQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CoachConversationServiceTest : RoomDaoTest() {
  @Test
  fun `sending immediately after attach is processed from the bounded queue`() = runTest {
    val workoutId = activeWorkout()
    val gateway = RecordingGateway()
    val conversation = conversation(gateway)

    conversation.attach(backgroundScope)
    assertTrue(conversation.send(workoutId, "Как идёт тренировка?"))
    val messages = db.coachDao().observeMessages(workoutId).first { it.size == 2 }
    assertEquals(listOf("user", "assistant"), messages.map { it.role })
    assertEquals("Готов помочь.", messages.last().text)
    assertEquals(1, gateway.calls)
    assertEquals("user", gateway.owners.single())
    assertEquals(0L, gateway.epochs.single())
  }

  @Test
  fun `oversized user text is rejected before it creates transcript or journal rows`() = runTest {
    val workoutId = activeWorkout()
    val conversation = conversation(RecordingGateway())
    conversation.attach(backgroundScope)

    assertFalse(conversation.send(workoutId, "x".repeat(4_001)))
    assertTrue(db.coachDao().messages(workoutId).isEmpty())
  }

  @Test
  fun `pending request from a previous process becomes interrupted and is not replayed`() =
      runTest {
        val workoutId = activeWorkout()
        val gateway = RecordingGateway()
        val control = control()
        control.appendMessage(
            "old-message",
            "user",
            workoutId,
            "user",
            "Не успел ответить",
            createdAt = 0,
            status = "PENDING",
        )
        val conversation = conversation(gateway, control)

        conversation.attach(backgroundScope)
        assertEquals(
            "INTERRUPTED",
            db.coachDao()
                .observeMessages(workoutId)
                .first { it.singleOrNull()?.status == "INTERRUPTED" }
                .single()
                .status,
        )
        assertEquals(0, gateway.calls)
      }

  @Test
  fun `local command retains send-time anchors and rejects a changed revision`() = runTest {
    val workoutId = activeWorkout()
    val gateway = RecordingGateway()
    val conversation = conversation(gateway)
    val set = db.workoutDao().getWorkoutFull(workoutId)!!.exercises.single().sets.single()

    conversation.attach(backgroundScope)
    assertTrue(conversation.send(workoutId, "поставь в текущем подходе 55 кг"))
    db.workoutDao().updateSet(set.copy(weightKg = 70.0))
    db.openHelper.writableDatabase.execSQL(
        "UPDATE workouts SET coachRevision = coachRevision + 1 WHERE id=?",
        arrayOf<Any?>(workoutId),
    )
    assertEquals(70.0, db.workoutDao().getSet(set.id)!!.weightKg!!, 0.0)
    assertEquals(0, gateway.calls)
    assertTrue(
        db.coachDao()
            .observeMessages(workoutId)
            .first { it.size == 2 }
            .last()
            .text
            .contains("состояние тренировки"),
    )
  }

  @Test
  fun `logout cancels a late model reply before it writes an assistant message`() = runTest {
    val workoutId = activeWorkout()
    val gateway = BlockingGateway()
    val conversation = conversation(gateway)
    conversation.attach(backgroundScope)
    assertTrue(conversation.send(workoutId, "Подожди ответ"))
    gateway.started.await()

    session.save(null)
    val messages =
        db.coachDao().observeMessages(workoutId).first {
          it.singleOrNull()?.status == "INTERRUPTED"
        }
    assertEquals(listOf("user"), messages.map { it.role })
    assertEquals("INTERRUPTED", messages.single().status)
    assertFalse(conversation.runningWorkouts.value.contains(workoutId))
  }

  @Test
  fun `finishing a workout cancels a late model reply`() = runTest {
    val workoutId = activeWorkout()
    val gateway = BlockingGateway()
    val conversation = conversation(gateway)
    conversation.attach(backgroundScope)
    assertTrue(conversation.send(workoutId, "Заканчиваю тренировку"))
    gateway.started.await()

    conversation.stopWorkout(workoutId)
    val messages =
        db.coachDao().observeMessages(workoutId).first {
          it.singleOrNull()?.status == "INTERRUPTED"
        }
    assertEquals(listOf("user"), messages.map { it.role })
    assertEquals("INTERRUPTED", messages.single().status)
  }

  @Test
  fun `stopping before collection drains the singleton queue instead of replaying on reattach`() =
      runTest {
        val workoutId = activeWorkout()
        val gateway = RecordingGateway()
        val conversation = conversation(gateway)
        conversation.attach(backgroundScope)
        assertTrue(conversation.send(workoutId, "Не выполняй после завершения"))

        conversation.stopWorkout(workoutId)
        conversation.detach()
        conversation.attach(backgroundScope)
        assertEquals(
            "INTERRUPTED",
            db.coachDao()
                .observeMessages(workoutId)
                .first { it.singleOrNull()?.status == "INTERRUPTED" }
                .single()
                .status,
        )
        assertEquals(0, gateway.calls)
      }

  private suspend fun activeWorkout(): String {
    val workoutId = insertWorkout("active")
    val exercise =
        db.exerciseDao()
            .insert(
                ExerciseEntity(
                    name = "Жим",
                    muscleGroup = MuscleGroup.CHEST,
                    type = ExerciseType.STRENGTH,
                ),
            )
    val section = insertWorkoutExercise(workoutId, exercise)
    insertSet(section, 0, weightKg = 50.0, reps = 8)
    db.openHelper.writableDatabase.execSQL(
        "INSERT OR REPLACE INTO backend_state (id, owner, generation, phase, initialMergeAcknowledged) VALUES (1, 'user', 0, 'OWNED', 1)",
    )
    return workoutId
  }

  private fun TestScope.control(): WorkoutControlService {
    val timer = RestTimerEngine(backgroundScope, WallClock { testScheduler.currentTime })
    val coordinator =
        WorkoutMutationCoordinator(
            db,
            db.workoutDao(),
            db.coachDao(),
            timer,
            session,
            WorkoutWriteQueue(),
        )
    return WorkoutControlService(db, coordinator, timer, session)
  }

  private fun TestScope.conversation(
      gateway: CoachModelGateway,
      control: WorkoutControlService = control(),
  ) = CoachConversationService(CoachAgent(gateway), control, db, session)

  private val session = FakeSession()

  private class FakeSession : BackendSessionStore {
    private val state =
        MutableStateFlow<BackendTokens?>(
            BackendTokens("user", "user@example.com", "access", "refresh")
        )
    override val session: StateFlow<BackendTokens?> = state
    private var epoch = 0L
    override val sessionEpoch: Long
      get() = epoch

    override fun save(tokens: BackendTokens?) {
      epoch++
      state.value = tokens
    }
  }

  private open class RecordingGateway : CoachModelGateway {
    var calls = 0
    val owners = mutableListOf<String>()
    val epochs = mutableListOf<Long?>()

    override suspend fun complete(
        expectedOwner: String,
        expectedSessionEpoch: Long?,
        messages: List<AiApiMessage>,
        tools: List<AiApiTool>,
    ): AiApiChatResponse {
      calls++
      owners += expectedOwner
      epochs += expectedSessionEpoch
      return AiApiChatResponse(
          choices =
              listOf(AiApiChoice(AiApiResponseMessage(content = JsonPrimitive("Готов помочь.")))),
      )
    }
  }

  private class BlockingGateway : RecordingGateway() {
    val started = CompletableDeferred<Unit>()
    private val never = CompletableDeferred<AiApiChatResponse>()

    override suspend fun complete(
        expectedOwner: String,
        expectedSessionEpoch: Long?,
        messages: List<AiApiMessage>,
        tools: List<AiApiTool>,
    ): AiApiChatResponse {
      calls++
      owners += expectedOwner
      epochs += expectedSessionEpoch
      started.complete(Unit)
      return never.await()
    }
  }
}
