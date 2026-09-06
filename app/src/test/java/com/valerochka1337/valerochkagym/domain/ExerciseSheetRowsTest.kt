package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.EquipmentRequirementState
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseSheetRowsTest {

  @Test
  fun `known requirements round trip across multiple muscles and equipment rows`() {
    val snapshot =
        ExerciseSheetRecord.Snapshot(
            EXERCISE_ID,
            200,
            "Жим",
            MuscleGroup.CHEST,
            ExerciseType.STRENGTH,
            true,
            mapOf(Muscle.UPPER_CHEST to 100, Muscle.TRICEPS to 50),
            equipmentRequirementState = EquipmentRequirementState.KNOWN,
            equipmentIds = setOf("dumbbells", "flat_bench"),
        )
    assertEquals(
        snapshot,
        ExerciseSheetRowParser.parse(ExerciseSheetRowMapper.rows(snapshot).asStrings())
            .records
            .single(),
    )
  }

  @Test
  fun `legacy zero only snapshot remains a valid empty custom map`() {
    val parsed =
        ExerciseSheetRowParser.parse(
            listOf(
                listOf(
                    EXERCISE_ID,
                    "200",
                    "false",
                    "Пустое",
                    "CORE",
                    "STRENGTH",
                    "true",
                    "ABS",
                    "0",
                ),
            )
        )
    val snapshot = parsed.records.single() as ExerciseSheetRecord.Snapshot
    assertEquals(emptyMap<Muscle, Int>(), snapshot.muscleLoads)
    assertEquals("Пустое", snapshot.name)
  }

  @Test
  fun `canonical version marker preserves explicit stabilizer zero`() {
    val snapshot =
        ExerciseSheetRecord.Snapshot(
            syncId = EXERCISE_ID,
            updatedAt = 201,
            name = "Канонический жим",
            muscleGroup = MuscleGroup.CHEST,
            type = ExerciseType.STRENGTH,
            isCustom = true,
            muscleLoads = mapOf(Muscle.UPPER_CHEST to 100, Muscle.ROTATOR_CUFF to 0),
        )

    assertEquals(
        snapshot,
        ExerciseSheetRowParser.parse(ExerciseSheetRowMapper.rows(snapshot).asStrings())
            .records
            .single(),
    )
  }

  @Test
  fun `legacy chest row expands to canonical chest roles and drops legacy zero`() {
    val parsed =
        ExerciseSheetRowParser.parse(
            listOf(
                ExerciseSheetRowMapper.HEADER_ROW,
                listOf(
                    EXERCISE_ID,
                    "200",
                    "false",
                    "Старый жим",
                    "CHEST",
                    "STRENGTH",
                    "true",
                    "CHEST",
                    "70",
                ),
                listOf(
                    EXERCISE_ID,
                    "200",
                    "false",
                    "Старый жим",
                    "CHEST",
                    "STRENGTH",
                    "true",
                    "TRICEPS",
                    "0",
                ),
            ),
        )
    val snapshot = parsed.records.single() as ExerciseSheetRecord.Snapshot
    val map = snapshot.muscleLoads
    assertEquals(100, map[Muscle.UPPER_CHEST])
    assertEquals(100, map[Muscle.LOWER_CHEST])
    assertEquals(null, map[Muscle.TRICEPS])
    assertTrue(snapshot.needsMuscleMapReview)
  }

  @Test
  fun `exercise header and rows keep the complete muscle map in stable order`() {
    val snapshot =
        ExerciseSheetRecord.Snapshot(
            syncId = EXERCISE_ID,
            updatedAt = 200,
            name = "Жим лёжа",
            muscleGroup = MuscleGroup.CHEST,
            type = ExerciseType.STRENGTH,
            isCustom = true,
            muscleLoads = linkedMapOf(Muscle.TRICEPS to 50, Muscle.UPPER_CHEST to 100),
        )

    assertEquals("Exercises!A:L", ExerciseSheetRowMapper.RANGE)
    assertEquals(
        snapshot,
        ExerciseSheetRowParser.parse(ExerciseSheetRowMapper.rows(snapshot).asStrings())
            .records
            .single(),
    )
  }

  @Test
  fun `exercise snapshot round trips with all fields and loads`() {
    val snapshot =
        ExerciseSheetRecord.Snapshot(
            syncId = EXERCISE_ID,
            updatedAt = 200,
            name = "Гребля",
            muscleGroup = MuscleGroup.BACK,
            type = ExerciseType.CARDIO,
            isCustom = false,
            muscleLoads = mapOf(Muscle.LATS to 100, Muscle.BICEPS to 50),
        )

    val parsed = ExerciseSheetRowParser.parse(ExerciseSheetRowMapper.rows(snapshot).asStrings())

    assertEquals(0, parsed.skippedRows)
    assertEquals(snapshot, parsed.records.single())
  }

  @Test
  fun `exercise parser selects the greatest updatedAt and tolerates identical duplicate rows`() {
    val old =
        ExerciseSheetRecord.Snapshot(
            syncId = EXERCISE_ID,
            updatedAt = 100,
            name = "Старое имя",
            muscleGroup = MuscleGroup.BACK,
            type = ExerciseType.STRENGTH,
            isCustom = true,
            muscleLoads = mapOf(Muscle.LATS to 100),
        )
    val current = old.copy(updatedAt = 200, name = "Новое имя")
    val currentRows = ExerciseSheetRowMapper.rows(current)
    val rows =
        listOf(ExerciseSheetRowMapper.HEADER_ROW) +
            ExerciseSheetRowMapper.rows(old).asStrings() +
            currentRows.asStrings() +
            currentRows.asStrings()

    val parsed = ExerciseSheetRowParser.parse(rows)

    assertEquals(0, parsed.skippedRows)
    assertEquals(current, parsed.records.single())
  }

  @Test
  fun `exercise parser preserves an empty muscle map with its explicit marker`() {
    val snapshot =
        ExerciseSheetRecord.Snapshot(
            syncId = EXERCISE_ID,
            updatedAt = 200,
            name = "Без разметки",
            muscleGroup = MuscleGroup.FULL_BODY,
            type = ExerciseType.TIMED,
            isCustom = true,
            muscleLoads = emptyMap(),
        )

    val rows = ExerciseSheetRowMapper.rows(snapshot)
    val parsed = ExerciseSheetRowParser.parse(rows.asStrings())

    assertEquals("", rows.single()[7])
    assertEquals("", rows.single()[8])
    assertEquals(2, rows.single()[9])
    assertEquals(snapshot, parsed.records.single())
  }

  @Test
  fun `newest malformed exercise snapshot is rejected without resurrecting an older version`() {
    val old =
        ExerciseSheetRecord.Snapshot(
            syncId = EXERCISE_ID,
            updatedAt = 100,
            name = "Старая версия",
            muscleGroup = MuscleGroup.LEGS,
            type = ExerciseType.STRENGTH,
            isCustom = false,
            muscleLoads = mapOf(Muscle.QUADS to 100),
        )
    val malformedLatest =
        listOf(
            EXERCISE_ID,
            "200",
            "false",
            "Новая версия",
            "LEGS",
            "STRENGTH",
            "false",
            "UNKNOWN_MUSCLE",
            "100",
        )
    val rows = ExerciseSheetRowMapper.rows(old).asStrings() + listOf(malformedLatest)

    val parsed = ExerciseSheetRowParser.parse(rows)

    assertEquals(1, parsed.skippedRows)
    assertTrue(parsed.records.isEmpty())
  }

  @Test
  fun `malformed current requirement state and empty marker conflicts are strict equipment corruption`() {
    val invalidState =
        listOf(
            EXERCISE_ID,
            "200",
            "false",
            "Жим",
            "CHEST",
            "STRENGTH",
            "true",
            "UPPER_CHEST",
            "100",
            "2",
            "NOT_A_STATE",
            "",
        )
    val emptyThenEquipment =
        listOf(
            listOf(
                "22222222-2222-4222-8222-222222222222",
                "200",
                "false",
                "Тяга",
                "BACK",
                "STRENGTH",
                "true",
                "LATS",
                "100",
                "2",
                "KNOWN",
                "",
            ),
            listOf(
                "22222222-2222-4222-8222-222222222222",
                "200",
                "false",
                "Тяга",
                "BACK",
                "STRENGTH",
                "true",
                "LATS",
                "100",
                "2",
                "KNOWN",
                "dumbbells",
            ),
        )
    val equipmentThenEmpty =
        listOf(
            listOf(
                "33333333-3333-4333-8333-333333333333",
                "200",
                "false",
                "Тяга обратная",
                "BACK",
                "STRENGTH",
                "true",
                "LATS",
                "100",
                "2",
                "KNOWN",
                "dumbbells",
            ),
            listOf(
                "33333333-3333-4333-8333-333333333333",
                "200",
                "false",
                "Тяга обратная",
                "BACK",
                "STRENGTH",
                "true",
                "LATS",
                "100",
                "2",
                "KNOWN",
                "",
            ),
        )

    val parsed =
        ExerciseSheetRowParser.parse(listOf(invalidState) + emptyThenEquipment + equipmentThenEmpty)

    assertEquals(3, parsed.skippedRows)
    assertTrue(parsed.records.isEmpty())
    assertTrue(parsed.hasInvalidEquipment)
  }

  @Test
  fun `exercise tombstone is distinct from an empty live snapshot`() {
    val rows =
        listOf(
            ExerciseSheetRowMapper.deletion(EXERCISE_ID, 300).map { it?.toString().orEmpty() },
        )

    val parsed = ExerciseSheetRowParser.parse(rows)

    assertEquals(ExerciseSheetRecord.Tombstone(EXERCISE_ID, 300), parsed.records.single())
  }

  @Test
  fun `exercise mapper rejects a malformed UUID and out of range contribution`() {
    assertThrows(IllegalArgumentException::class.java) {
      ExerciseSheetRowMapper.rows(
          ExerciseSheetRecord.Snapshot(
              syncId = "not-a-uuid",
              updatedAt = 1,
              name = "Тест",
              muscleGroup = MuscleGroup.CHEST,
              type = ExerciseType.STRENGTH,
              isCustom = true,
              muscleLoads = emptyMap(),
          ),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      ExerciseSheetRowMapper.rows(
          ExerciseSheetRecord.Snapshot(
              syncId = EXERCISE_ID,
              updatedAt = 1,
              name = "Тест",
              muscleGroup = MuscleGroup.CHEST,
              type = ExerciseType.STRENGTH,
              isCustom = true,
              muscleLoads = mapOf(Muscle.UPPER_CHEST to 101),
          ),
      )
    }
  }

  private fun List<List<Any?>>.asStrings(): List<List<String>> = map { row ->
    row.map { it?.toString().orEmpty() }
  }

  private companion object {
    const val EXERCISE_ID = "11111111-1111-4111-8111-111111111111"
  }
}
