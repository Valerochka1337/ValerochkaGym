package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import com.valerochka1337.valerochkagym.data.db.entity.*
import com.valerochka1337.valerochkagym.service.RestTimerEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WorkoutChangeSummaryTest : RoomDaoTest() {
  private val done = SnapshotSet("done", 0, true, 60.0, 8, null)
  private val next = SnapshotSet("next", 1, false, 60.0, 8, null)
  private val snapshot =
      WorkoutSnapshot(
          "owner",
          "workout",
          0,
          listOf(SnapshotExercise("section", 1, name = "Жим", sets = listOf(done, next))),
      )

  private suspend fun TestScope.summary(op: WorkoutChangeSet.Operation) =
      summary(snapshot, WorkoutChangeSet.Packet(listOf(op)))

  private suspend fun TestScope.summary(
      snapshot: WorkoutSnapshot,
      packet: WorkoutChangeSet.Packet,
  ): WorkoutChangeSummary.Summary {
    db.workoutDao().deleteWorkout(snapshot.workoutId)
    db.workoutDao().insertWorkout(WorkoutEntity(snapshot.workoutId, null, "Тренировка", 0, null))
    val exercises =
        (snapshot.exercises.map { it.exerciseId to it.name } + (2L to "Отжимания")).distinctBy {
          it.first
        }
    for ((id, name) in exercises) if (db.exerciseDao().getById(id) == null)
        db.exerciseDao()
            .insert(
                ExerciseEntity(
                    id = id,
                    name = name,
                    muscleGroup = MuscleGroup.CHEST,
                    type = ExerciseType.STRENGTH,
                )
            )
    for (row in snapshot.exercises) {
      val id =
          db.workoutDao()
              .insertWorkoutExercise(
                  WorkoutExerciseEntity(
                      workoutId = snapshot.workoutId,
                      exerciseId = row.exerciseId,
                      sectionId = row.sectionId,
                      position = row.position,
                  )
              )
      for (set in row.sets) db.workoutDao()
          .insertSet(
              WorkoutSetEntity(
                  workoutExerciseId = id,
                  setIndex = set.setIndex,
                  syncId = set.syncId,
                  weightKg = set.weightKg,
                  reps = set.reps,
                  durationSec = set.durationSec,
                  isCompleted = set.completed,
              )
          )
    }
    db.openHelper.writableDatabase.execSQL(
        "INSERT OR REPLACE INTO backend_state (id, owner, generation, phase, initialMergeAcknowledged) VALUES (1, 'owner', 0, 'OWNED', 1)"
    )
    val session =
        object : BackendSessionStore {
          override val session =
              MutableStateFlow<BackendTokens?>(BackendTokens("owner", "a@b.c", "a", "r"))

          override fun save(tokens: BackendTokens?) {
            session.value = tokens
          }
        }
    val editor =
        WorkoutEditor(
            db,
            db.workoutDao(),
            db.coachDao(),
            RestTimerEngine(backgroundScope) { 0L },
            session,
            WorkoutWriteQueue(),
        )
    val before = db.workoutDao().getWorkoutFull(snapshot.workoutId)
    val proposal =
        requireNotNull(editor.saveProposal("owner", snapshot.workoutId, packet, 0, Long.MAX_VALUE))
    assertEquals(before, db.workoutDao().getWorkoutFull(snapshot.workoutId))
    return WorkoutChangeSummary.Summary(proposal.beforeSummary, proposal.afterSummary)
  }

  @Test
  fun `result preview names the exact set and changed completion state`() = runTest {
    val result = summary(WorkoutChangeSet.Operation.EditSet("next", reps = 6, recordResult = true))
    assertTrue(result.before.contains("Жим, подход 2"))
    assertTrue(result.before.contains("8 повт."))
    assertTrue(result.before.contains("не выполнен"))
    assertTrue(result.after.contains("6 повт."))
    assertTrue(result.after.endsWith("выполнен"))
    assertFalse(result.after.contains("не выполнен"))
  }

  @Test
  fun `replacement preview uses resolved new load and preserves completed work`() = runTest {
    val result =
        summary(
            WorkoutChangeSet.Operation.ReplaceRemaining("section", "new", 2, listOf("next"), 20.0)
        )
    assertTrue(result.before.contains("60 кг"))
    assertTrue(result.after.contains("20 кг"))
    assertFalse(result.after.contains("60 кг"))
    assertTrue(result.after.contains("Сохранить 1 выполненных подходов"))
    assertTrue(result.after.contains("Отжимания"))
  }

  @Test
  fun `unresolved replacement and undo cannot render misleading approval`() = runTest {
    listOf(
            WorkoutChangeSet.Operation.ReplaceRemaining("section", "new", 2, listOf("next"), null),
            WorkoutChangeSet.Operation.UndoLast,
        )
        .forEach { op ->
          try {
            summary(op)
            fail("Missing preview facts must be rejected")
          } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
        }
  }

  @Test
  fun `clearing a load explicitly shows that no values remain`() = runTest {
    val result =
        summary(
            WorkoutChangeSet.Operation.EditSet("next", clearFields = setOf("weight_kg", "reps"))
        )
    assertTrue(result.after.contains("значения не заданы"))
    assertFalse(result.after.contains("60 кг"))
  }

  @Test
  fun `second move previews the order produced by the first move`() = runTest {
    val workout =
        snapshot.copy(
            exercises =
                listOf(
                    SnapshotExercise("a", 1, name = "A", position = 0),
                    SnapshotExercise("b", 2, name = "B", position = 1),
                    SnapshotExercise("c", 3, name = "C", position = 2),
                )
        )
    val result =
        summary(
            workout,
            WorkoutChangeSet.Packet(
                listOf(
                    WorkoutChangeSet.Operation.MoveExercise("a", 2),
                    WorkoutChangeSet.Operation.MoveExercise("b", 1),
                )
            ),
        )
    assertTrue(result.before.endsWith("Шаг 2:\n1. B\n2. C\n3. A"))
    assertTrue(result.after.endsWith("Шаг 2:\n1. C\n2. B\n3. A"))
  }

  @Test
  fun `successive edits and context changes use the preceding values`() = runTest {
    val result =
        summary(
            snapshot,
            WorkoutChangeSet.Packet(
                listOf(
                    WorkoutChangeSet.Operation.EditSet("next", weightKg = 50.0),
                    WorkoutChangeSet.Operation.EditSet("next", reps = 6),
                    WorkoutChangeSet.Operation.SetAvailableTime(20),
                    WorkoutChangeSet.Operation.SetAvailableTime(10),
                )
            ),
        )
    assertTrue(result.before.contains("Шаг 2:\nЖим, подход 2: 50 кг · 8 повт."))
    assertTrue(result.after.contains("Шаг 2:\nЖим, подход 2: 50 кг · 6 повт."))
    assertTrue(result.before.endsWith("Шаг 4:\nДоступное время: 20 мин"))
    assertTrue(result.after.endsWith("Шаг 4:\nОставшееся доступное время: 10 мин"))
  }

  @Test
  fun `impossible replacement and deletion cannot become approval cards`() = runTest {
    val workout =
        snapshot.copy(
            exercises =
                snapshot.exercises.map {
                  it.copy(sets = it.sets + next.copy(syncId = "third", setIndex = 2))
                }
        )
    listOf(
            WorkoutChangeSet.Operation.ReplaceRemaining("section", "new", 2, listOf("next"), 20.0),
            WorkoutChangeSet.Operation.DeleteSet("done"),
            WorkoutChangeSet.Operation.DeleteExercise("section"),
            WorkoutChangeSet.Operation.ReorderExercises(listOf("section", "section")),
        )
        .forEach { op ->
          try {
            summary(workout, WorkoutChangeSet.Packet(listOf(op)))
            fail("Invalid packet must be rejected before approval")
          } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
        }
  }
}
