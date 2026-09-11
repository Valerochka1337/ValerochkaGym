package com.valerochka1337.valerochkagym.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WorkoutApprovalFormatterTest {
  private val set = SnapshotSet("s", 0, false, 60.0, 10, null)
  private val a = SnapshotExercise("a", 1, name = "Жим", position = 0, sets = listOf(set))
  private val b = SnapshotExercise("b", 2, name = "Тяга", position = 1)
  private val c = SnapshotExercise("c", 3, name = "Присед", position = 2)

  private fun state(vararg exercises: SnapshotExercise) =
      WorkoutSnapshot("owner", "w", 0, exercises.toList())

  private fun preview(old: WorkoutSnapshot, new: WorkoutSnapshot, op: WorkoutChangeSet.Operation) =
      WorkoutApprovalFormatter.describe(
          listOf(WorkoutChangeSummary.Step(op, old, new)),
          mapOf(1L to "Жим", 2L to "Тяга", 3L to "Присед"),
      )

  @Test
  fun `set edit displays only changed fields and round trips persisted preview`() {
    val result =
        preview(
            state(a),
            state(a.copy(sets = listOf(set.copy(weightKg = 55.0)))),
            WorkoutChangeSet.Operation.EditSet("s", weightKg = 55.0),
        )
    assertEquals(listOf("Вес: 60 кг → 55 кг"), result.actions.single().details)
    assertFalse(result.text().contains("Повторения"))
    assertEquals(result, WorkoutApprovalPreview.decode(Json.encodeToString(result)))
    assertNull(WorkoutApprovalPreview.decode("broken"))
  }

  @Test
  fun `replacement combines subsequent set edits into the final load`() {
    val middle = state(b.copy(sets = listOf(set.copy(weightKg = 20.0))))
    val final = state(b.copy(sets = listOf(set.copy(weightKg = 25.0))))
    val result =
        WorkoutApprovalFormatter.describe(
            listOf(
                WorkoutChangeSummary.Step(
                    WorkoutChangeSet.Operation.ReplaceRemaining("a", "b", 2, listOf("s"), 20.0),
                    state(a),
                    middle,
                ),
                WorkoutChangeSummary.Step(
                    WorkoutChangeSet.Operation.EditSet("s", weightKg = 25.0),
                    middle,
                    final,
                ),
            ),
            emptyMap(),
        )
    assertEquals(1, result.actions.size)
    assertTrue(result.text().contains("25 кг"))
    assertFalse(result.text().contains("20 кг"))
    assertFalse(result.text().contains("60 кг"))
  }

  @Test
  fun `explicit adjacent move names its destination while reorder names the swap`() {
    val old = state(a, b, c)
    val new = state(b.copy(position = 0), a.copy(position = 1), c)
    assertEquals(
        "move",
        preview(old, new, WorkoutChangeSet.Operation.MoveExercise("a", 1)).actions.single().kind,
    )
    assertTrue(
        preview(old, new, WorkoutChangeSet.Operation.MoveExercise("a", 1))
            .text()
            .contains("после «Тяга»")
    )
    assertEquals(
        "swap",
        preview(old, new, WorkoutChangeSet.Operation.ReorderExercises(listOf("b", "a", "c")))
            .actions
            .single()
            .kind,
    )
  }

  @Test
  fun `removing the last set displays removal of the exercise`() {
    val result = preview(state(a), state(), WorkoutChangeSet.Operation.DeleteSet("s"))
    assertEquals("Убрать «Жим» из тренировки", result.actions.single().title)
  }

  @Test
  fun `unchanged packet has no approval actions`() {
    assertTrue(
        preview(state(a), state(a), WorkoutChangeSet.Operation.EditSet("s", weightKg = 60.0))
            .actions
            .isEmpty()
    )
  }

  @Test
  fun `non adjacent identical set edits keep exact separate numbers`() {
    val original = a.copy(sets = (0..2).map { set.copy(syncId = "s$it", setIndex = it) })
    val changed =
        original.copy(
            sets = original.sets.map { if (it.setIndex != 1) it.copy(weightKg = null) else it }
        )
    val result =
        preview(
            state(original),
            state(changed),
            WorkoutChangeSet.Operation.EditSet("s0", clearFields = setOf("weight_kg")),
        )
    assertEquals(listOf("Жим · подход 1", "Жим · подход 3"), result.actions.map { it.title })
    assertTrue(result.actions.all { it.details == listOf("Вес: 60 кг → не задано") })
  }

  @Test
  fun `context additions and removals show their direction`() {
    val old = state(a).copy(availableTimeMinutes = 30, excludedExerciseIds = setOf(1))
    val new = old.copy(availableTimeMinutes = 20, excludedExerciseIds = setOf(2))
    val result = preview(old, new, WorkoutChangeSet.Operation.SetAvailableTime(20)).text()
    assertTrue(result.contains("30 мин → 20 мин"))
    assertTrue(result.contains("Исключить до конца тренировки: Тяга"))
    assertTrue(result.contains("Снять исключение: Жим"))
  }

  @Test
  fun `replacement preserves completed work and exposes changed feelings`() {
    val done = set.copy(syncId = "done", completed = true)
    val original = a.copy(sets = listOf(done, set.copy(setIndex = 1)))
    val remaining = a.copy(sets = listOf(done))
    val replacement =
        b.copy(sets = listOf(set.copy(weightKg = null, reportedFeelings = setOf("FATIGUE"))))
    val result =
        preview(
            state(original),
            state(remaining, replacement),
            WorkoutChangeSet.Operation.ReplaceRemaining("a", "b", 2, listOf("s"), null),
        )
    assertEquals(2, result.actions.size)
    assertTrue(result.text().contains("сохранятся: 1"))
    assertTrue(result.text().contains("вес не задан"))
    assertTrue(result.text().contains("Ощущения: не указаны → усталость"))
  }

  @Test
  fun `complex reorder lists only exercises whose positions change`() {
    val d = SnapshotExercise("d", 4, name = "Планка", position = 3)
    val e = SnapshotExercise("e", 5, name = "Бег", position = 4)
    val result =
        preview(
            state(a, b, c, d, e),
            state(c.copy(position = 0), b, d.copy(position = 2), a.copy(position = 3), e),
            WorkoutChangeSet.Operation.ReorderExercises(listOf("c", "b", "d", "a", "e")),
        )
    assertEquals("Изменить порядок упражнений", result.actions.single().title)
    assertFalse(result.text().contains("Бег"))
    assertFalse(result.text().contains("Тяга"))
  }

  @Test
  fun `adding and deleting sets show exact identities and final load`() {
    val original = a.copy(sets = listOf(set, set.copy(syncId = "second", setIndex = 1)))
    val updated =
        a.copy(sets = listOf(set, set.copy(syncId = "new", setIndex = 1, weightKg = 40.0)))
    val result =
        preview(state(original), state(updated), WorkoutChangeSet.Operation.DeleteSet("second"))
    assertEquals(listOf("delete", "add"), result.actions.map { it.kind })
    assertTrue(result.actions[0].title.endsWith("2"))
    assertTrue(result.actions[1].details.single().contains("40 кг"))
  }

  @Test
  fun `undo of rest only context displays the restored duration`() {
    val result =
        preview(
            state(a).copy(futureRestSeconds = 90),
            state(a).copy(futureRestSeconds = 60),
            WorkoutChangeSet.Operation.UndoLast,
        )
    assertEquals("undo", result.actions.first().kind)
    assertTrue(result.text().contains("90 с → 60 с"))
  }

  @Test
  fun `added exercise result status remains visible`() {
    val result =
        preview(
            state(a),
            state(a, b.copy(sets = listOf(set.copy(syncId = "new", completed = true)))),
            WorkoutChangeSet.Operation.AddExercise(2),
        )
    assertTrue(result.text().contains("Отметить выполненным"))
  }

  @Test
  fun `same named exercises keep distinct set edit labels`() {
    val second = a.copy(sectionId = "second", position = 1, sets = listOf(set.copy(syncId = "s2")))
    val result =
        preview(
            state(a, second),
            state(a, second.copy(sets = listOf(set.copy(syncId = "s2", weightKg = 50.0)))),
            WorkoutChangeSet.Operation.EditSet("s2", weightKg = 50.0),
        )
    assertEquals("Жим (позиция 2) · подход 1", result.actions.single().title)
  }

  @Test
  fun `rest effects are explicit and serialization rejects unsupported versions`() {
    val result =
        preview(state(a), state(a), WorkoutChangeSet.Operation.Rest(RestAction.EXTEND, "rest", 30))
    assertEquals("Добавить 30 с к текущему отдыху", result.actions.single().title)
    assertNull(WorkoutApprovalPreview.decode(Json.encodeToString(result.copy(version = 2))))
  }

  @Test
  fun `replacement does not hide a set added back to the retained source`() {
    val done = set.copy(syncId = "done", completed = true)
    val source = a.copy(sets = listOf(done, set.copy(setIndex = 1)))
    val retained = a.copy(sets = listOf(done, set.copy(syncId = "extra", setIndex = 1)))
    val destination = b.copy(sets = listOf(set.copy(weightKg = 20.0)))
    val result =
        preview(
            state(source),
            state(retained, destination),
            WorkoutChangeSet.Operation.ReplaceRemaining("a", "b", 2, listOf("s"), 20.0),
        )
    assertEquals(listOf("replace", "add"), result.actions.map { it.kind })
    assertTrue(result.actions.last().title.contains("добавить подходы 2"))
  }
}
