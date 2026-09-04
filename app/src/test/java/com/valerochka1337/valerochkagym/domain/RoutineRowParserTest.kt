package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.relation.RoutineExerciseWithExercise
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineRowParserTest {

  @Test
  fun `parsing a routine snapshot restores exercises rest and planned sets`() {
    val routine = routine(updatedAt = 100)
    val rows = listOf(RoutineRowMapper.HEADER_ROW) + RoutineRowMapper.rows(routine)

    val result = RoutineRowParser.parse(rows.map { row -> row.map { it?.toString().orEmpty() } })

    assertEquals(0, result.skippedRows)
    val parsed = result.routines.single()
    assertEquals("routine-1", parsed.syncId)
    assertEquals(100, parsed.updatedAt)
    assertEquals("Ноги", parsed.name)
    assertEquals("Сначала присед", parsed.note)
    assertFalse(parsed.isDeleted)
    assertEquals(2, parsed.exercises.size)
    assertEquals(listOf("Присед", "Планка"), parsed.exercises.map { it.name })
    assertTrue(parsed.exercises.all { it.syncId != null })
    assertEquals(90, parsed.exercises.first().restSeconds)
    assertEquals(
        listOf(PlannedSet(weightKg = 100.0, reps = 5), PlannedSet(weightKg = 100.0, reps = 5)),
        parsed.exercises.first().plannedSets,
    )
    assertEquals(ExerciseType.TIMED, parsed.exercises.last().type)
  }

  @Test
  fun `v9 rows retain base exercise data and ignore variant id`() {
    val row =
        RoutineRowMapper.rows(routine(updatedAt = 100))
            .first()
            .map { it?.toString().orEmpty() }
            .toMutableList()
    row[12] = "11111111-1111-1111-1111-111111111111"

    val parsed = RoutineRowParser.parse(listOf(RoutineRowMapper.HEADER_ROW, row)).routines.single()

    assertEquals("Присед", parsed.exercises.single().name)
    assertEquals(90, parsed.exercises.single().restSeconds)
    assertTrue(parsed.exercises.single().syncId != null)
  }

  @Test
  fun `parsing snapshots selects the version with the greatest updatedAt`() {
    val old = routine(updatedAt = 100, name = "Старое имя")
    val current = routine(updatedAt = 200, name = "Новое имя")
    val rows =
        buildList {
              add(RoutineRowMapper.HEADER_ROW)
              addAll(RoutineRowMapper.rows(old))
              addAll(RoutineRowMapper.rows(current))
            }
            .map { row -> row.map { it?.toString().orEmpty() } }

    val parsed = RoutineRowParser.parse(rows).routines.single()

    assertEquals(200, parsed.updatedAt)
    assertEquals("Новое имя", parsed.name)
  }

  @Test
  fun `parsing a newer tombstone marks the routine deleted`() {
    val rows =
        buildList {
              add(RoutineRowMapper.HEADER_ROW)
              addAll(RoutineRowMapper.rows(routine(updatedAt = 100)))
              add(RoutineRowMapper.deletion("routine-1", 200))
            }
            .map { row -> row.map { it?.toString().orEmpty() } }

    val parsed = RoutineRowParser.parse(rows).routines.single()

    assertTrue(parsed.isDeleted)
    assertEquals(200, parsed.updatedAt)
    assertTrue(parsed.exercises.isEmpty())
  }

  @Test
  fun `legacy routine rows without exercise ids remain readable by name`() {
    val routine = routine(updatedAt = 100)
    val rows =
        listOf(RoutineRowMapper.LEGACY_HEADER_ROW) +
            RoutineRowMapper.rows(routine).map { it.dropLast(2) }

    val parsed =
        RoutineRowParser.parse(
                rows.map { row -> row.map { it?.toString().orEmpty() } },
            )
            .routines
            .single()

    assertEquals(listOf("Присед", "Планка"), parsed.exercises.map { it.name })
    assertTrue(parsed.exercises.all { it.syncId == null })
  }

  private fun routine(updatedAt: Long, name: String = "Ноги"): RoutineWithExercises {
    val squat =
        ExerciseEntity(
            id = 1,
            name = "Присед",
            muscleGroup = MuscleGroup.LEGS,
            type = ExerciseType.STRENGTH,
        )
    val plank =
        ExerciseEntity(
            id = 2,
            name = "Планка",
            muscleGroup = MuscleGroup.CORE,
            type = ExerciseType.TIMED,
        )
    return RoutineWithExercises(
        routine =
            RoutineEntity(
                id = 1,
                syncId = "routine-1",
                updatedAt = updatedAt,
                name = name,
                note = "Сначала присед",
            ),
        exercises =
            listOf(
                RoutineExerciseWithExercise(
                    routineExercise =
                        RoutineExerciseEntity(
                            id = 1,
                            routineId = 1,
                            exerciseId = 1,
                            position = 0,
                            restSeconds = 90,
                            plannedSets =
                                listOf(
                                    PlannedSet(weightKg = 100.0, reps = 5),
                                    PlannedSet(weightKg = 100.0, reps = 5),
                                ),
                        ),
                    exercise = squat,
                ),
                RoutineExerciseWithExercise(
                    routineExercise =
                        RoutineExerciseEntity(
                            id = 2,
                            routineId = 1,
                            exerciseId = 2,
                            position = 1,
                            restSeconds = 30,
                            plannedSets = listOf(PlannedSet(durationSec = 60)),
                        ),
                    exercise = plank,
                ),
            ),
    )
  }
}
