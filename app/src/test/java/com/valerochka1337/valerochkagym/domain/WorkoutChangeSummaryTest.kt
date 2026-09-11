package com.valerochka1337.valerochkagym.domain

import org.junit.Assert.*
import org.junit.Test

class WorkoutChangeSummaryTest {
  private val done = SnapshotSet("done", 0, true, 60.0, 8, null)
  private val next = SnapshotSet("next", 1, false, 60.0, 8, null)
  private val snapshot =
      WorkoutSnapshot(
          "owner",
          "workout",
          0,
          listOf(SnapshotExercise("section", 1, name = "Жим", sets = listOf(done, next))),
      )

  private fun summary(op: WorkoutChangeSet.Operation) =
      WorkoutChangeSummary.describe(
          snapshot,
          WorkoutChangeSet.Packet(listOf(op)),
          mapOf(2L to "Отжимания"),
      )

  @Test
  fun `result preview names the exact set and changed completion state`() {
    val result = summary(WorkoutChangeSet.Operation.EditSet("next", reps = 6, recordResult = true))
    assertTrue(result.before.contains("Жим, подход 2"))
    assertTrue(result.before.contains("8 повт."))
    assertTrue(result.before.contains("не выполнен"))
    assertTrue(result.after.contains("6 повт."))
    assertTrue(result.after.endsWith("выполнен"))
    assertFalse(result.after.contains("не выполнен"))
  }

  @Test
  fun `replacement preview uses resolved new load and preserves completed work`() {
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
  fun `unresolved replacement and undo cannot render misleading approval`() {
    listOf(
            WorkoutChangeSet.Operation.ReplaceRemaining("section", "new", 2, listOf("next"), null),
            WorkoutChangeSet.Operation.UndoLast,
        )
        .forEach { op ->
          try {
            summary(op)
            fail("Missing preview facts must be rejected")
          } catch (_: IllegalArgumentException) {}
        }
  }

  @Test
  fun `clearing a load explicitly shows that no values remain`() {
    val result =
        summary(
            WorkoutChangeSet.Operation.EditSet("next", clearFields = setOf("weight_kg", "reps"))
        )
    assertTrue(result.after.contains("значения не заданы"))
    assertFalse(result.after.contains("60 кг"))
  }

  @Test
  fun `second move previews the order produced by the first move`() {
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
        WorkoutChangeSummary.describe(
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
  fun `successive edits and context changes use the preceding values`() {
    val result =
        WorkoutChangeSummary.describe(
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
  fun `impossible replacement and deletion cannot become approval cards`() {
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
            WorkoutChangeSummary.describe(
                workout,
                WorkoutChangeSet.Packet(listOf(op)),
                mapOf(2L to "Тяга"),
            )
            fail("Invalid packet must be rejected before approval")
          } catch (_: IllegalArgumentException) {}
        }
  }
}
