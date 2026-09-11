package com.valerochka1337.valerochkagym.domain

import org.junit.Assert.*
import org.junit.Test

class LocalWorkoutCommandParserTest {
  private val anchors = CommandAuthority.Anchors("current", "previous", "next")

  private fun parse(text: String) =
      LocalWorkoutCommandParser.parse(text, anchors, "section", "rest")

  private fun operation(text: String) = parse(text)?.packet?.operations?.single()

  @Test
  fun `result correction anchors the explicitly named previous set`() {
    assertEquals(
        WorkoutChangeSet.Operation.EditSet("previous", reps = 6, recordResult = true),
        operation("В прошлом подходе сделал 6 повторений"),
    )
  }

  @Test
  fun `next target changes do not mark a result completed`() {
    assertEquals(
        WorkoutChangeSet.Operation.EditSet("next", weightKg = 42.5),
        operation("поставь в следующем подходе 42,5 кг"),
    )
    assertEquals(
        WorkoutChangeSet.Operation.EditSet("next", reps = 6),
        operation("установи в следующем подходе 6 повторений"),
    )
  }

  @Test
  fun `duration speed and incline use typed units`() {
    assertEquals(
        WorkoutChangeSet.Operation.EditSet("current", durationSec = 120),
        operation("поставь в текущем подходе 2 минуты"),
    )
    assertEquals(
        WorkoutChangeSet.Operation.EditSet("next", speedKmh = 8.5),
        operation("поставь в следующем подходе 8,5 км/ч"),
    )
    assertEquals(
        WorkoutChangeSet.Operation.EditSet("next", inclinePct = 5.0),
        operation("поставь в следующем подходе 5 процентов"),
    )
  }

  @Test
  fun `rest commands bind the captured timer identity`() {
    assertEquals(
        WorkoutChangeSet.Operation.Rest(RestAction.EXTEND, "rest", 60),
        operation("увеличь отдых на 1 минуту"),
    )
    assertEquals(
        WorkoutChangeSet.Operation.Rest(RestAction.SKIP, "rest"),
        operation("пропусти отдых"),
    )
    assertNull(
        LocalWorkoutCommandParser.parse("увеличь отдых на 30 секунд", anchors, "section", null)
    )
  }

  @Test
  fun `available time is an explicit context operation`() {
    assertEquals(WorkoutChangeSet.Operation.SetAvailableTime(20), operation("осталось 20 минут"))
  }

  @Test
  fun `ambiguous or compound instructions never receive authority`() {
    listOf(
            "слишком тяжело",
            "увеличь отдых",
            "добавь подход и снизь вес",
            "не добавь подход",
            "поставь в следующем подходе 6,5 повторений",
            "увеличь отдых на 999999999999999999999 секунд",
        )
        .forEach { assertNull(it, parse(it)) }
  }

  @Test
  fun `authority cannot be reused after the current set advances`() {
    val parsed = parse("добавь подход")!!
    assertTrue(parsed.authority.authorizes(parsed.packet, anchors))
    assertFalse(parsed.authority.authorizes(parsed.packet, anchors.copy(currentSetId = "next")))
    assertFalse(
        parsed.authority.authorizes(
            WorkoutChangeSet.Packet(
                parsed.packet.operations + WorkoutChangeSet.Operation.DeleteSet("next")
            ),
            anchors,
        )
    )
  }

  @Test
  fun `missing set section or timer anchors never grant dependent commands`() {
    val missing = CommandAuthority.Anchors(null, null, null)
    listOf(
            "добавь подход",
            "пропусти отдых",
            "заверши отдых",
            "увеличь отдых на 30 секунд",
            "отметь текущий подход выполненным",
            "отметь прошлый подход выполненным",
            "отметь следующий подход выполненным",
            "сними отметку выполнения текущего подхода",
            "сними отметку выполнения прошлого подхода",
            "сними отметку выполнения следующего подхода",
            "удали текущий подход",
            "удали следующий подход",
            "в текущем подходе сделал 6 повторений",
            "в прошлом подходе сделал 6 повторений",
            "поставь в текущем подходе 30 кг",
            "поставь в следующем подходе 6 повторений",
        )
        .forEach { assertNull(it, LocalWorkoutCommandParser.parse(it, missing, null, null)) }
    assertNotNull(
        LocalWorkoutCommandParser.parse("запусти отдых на 60 секунд", missing, null, null)
    )
    assertNotNull(
        LocalWorkoutCommandParser.parse(
            "установи последующий отдых на 60 секунд",
            missing,
            null,
            null,
        )
    )
  }

  @Test
  fun `instead of result requires exactly one matching anchored target`() {
    fun snapshot(previousReps: Int) =
        WorkoutSnapshot(
            "account",
            "workout",
            0,
            listOf(
                SnapshotExercise(
                    "section",
                    1,
                    sets =
                        listOf(
                            SnapshotSet("previous", 0, true, 40.0, previousReps, null),
                            SnapshotSet("current", 1, false, 40.0, 8, null),
                        ),
                )
            ),
            currentSetId = "current",
            previousSetId = "previous",
        )
    assertNull(LocalWorkoutCommandParser.parse("сделал 6 вместо 8", snapshot(8)))
    assertEquals(
        WorkoutChangeSet.Operation.EditSet("current", reps = 6, recordResult = true),
        LocalWorkoutCommandParser.parse("сделал 6 вместо 8", snapshot(10))!!
            .packet
            .operations
            .single(),
    )
  }
}
