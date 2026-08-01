package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutExerciseWithSets
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutRowMapperTest {

    // region column contract

    @Test
    fun `HEADER_ROW matches the expected column order`() {
        assertEquals(
            listOf(
                "workout_id",
                "date",
                "start_time",
                "workout_name",
                "exercise",
                "muscle_group",
                "type",
                "set_index",
                "weight_kg",
                "reps",
                "duration_sec",
                "speed_kmh",
                "incline_pct",
                "volume",
            ),
            WorkoutRowMapper.HEADER_ROW,
        )
    }

    @Test
    fun `strength set maps to a full row with human set index and volume`() {
        val workout = workoutFull(
            id = "w-1",
            name = "Ноги",
            startedAt = STARTED_AT,
            exercises = listOf(
                exercise(
                    name = "Присед",
                    muscleGroup = MuscleGroup.LEGS,
                    type = ExerciseType.STRENGTH,
                    position = 0,
                    sets = listOf(
                        set(setIndex = 0, weightKg = 100.0, reps = 5, isCompleted = true),
                    ),
                ),
            ),
        )

        val row = WorkoutRowMapper.rows(workout).single()

        assertEquals("w-1", row[0])
        assertEquals(expectedDate(STARTED_AT), row[1])
        assertEquals(expectedTime(STARTED_AT), row[2])
        assertEquals("Ноги", row[3])
        assertEquals("Присед", row[4])
        assertEquals("Ноги", row[5])
        assertEquals("Силовое", row[6])
        assertEquals(1, row[7]) // setIndex 0 -> human 1
        assertEquals(100.0, row[8])
        assertEquals(5, row[9])
        assertNull(row[10])
        assertNull(row[11])
        assertNull(row[12])
        assertEquals(500.0, row[13]) // volume = 100 * 5
    }

    @Test
    fun `timed set fills duration and leaves volume null`() {
        val workout = workoutFull(
            exercises = listOf(
                exercise(
                    name = "Планка",
                    muscleGroup = MuscleGroup.CORE,
                    type = ExerciseType.TIMED,
                    position = 0,
                    sets = listOf(set(setIndex = 0, durationSec = 60, isCompleted = true)),
                ),
            ),
        )

        val row = WorkoutRowMapper.rows(workout).single()

        assertEquals("На время", row[6])
        assertNull(row[8]) // weight
        assertNull(row[9]) // reps
        assertEquals(60, row[10]) // duration
        assertNull(row[13]) // volume
    }

    @Test
    fun `cardio set fills speed and incline and leaves volume null`() {
        val workout = workoutFull(
            exercises = listOf(
                exercise(
                    name = "Дорожка",
                    muscleGroup = MuscleGroup.CARDIO,
                    type = ExerciseType.CARDIO,
                    position = 0,
                    sets = listOf(
                        set(
                            setIndex = 0,
                            durationSec = 600,
                            speedKmh = 10.0,
                            inclinePct = 5.0,
                            isCompleted = true,
                        ),
                    ),
                ),
            ),
        )

        val row = WorkoutRowMapper.rows(workout).single()

        assertEquals("Кардио", row[6])
        assertEquals(600, row[10])
        assertEquals(10.0, row[11])
        assertEquals(5.0, row[12])
        assertNull(row[13]) // volume
    }

    @Test
    fun `each row has exactly HEADER_ROW columns`() {
        val workout = workoutFull(
            exercises = listOf(
                exercise(
                    name = "Присед",
                    muscleGroup = MuscleGroup.LEGS,
                    type = ExerciseType.STRENGTH,
                    position = 0,
                    sets = listOf(set(setIndex = 0, weightKg = 80.0, reps = 6, isCompleted = true)),
                ),
            ),
        )

        WorkoutRowMapper.rows(workout).forEach { row ->
            assertEquals(WorkoutRowMapper.HEADER_ROW.size, row.size)
        }
    }

    @Test
    fun `muscle group and type columns use russian display names`() {
        val workout = workoutFull(
            exercises = listOf(
                exercise(
                    name = "Жим",
                    muscleGroup = MuscleGroup.CHEST,
                    type = ExerciseType.STRENGTH,
                    position = 0,
                    sets = listOf(set(setIndex = 0, weightKg = 60.0, reps = 8, isCompleted = true)),
                ),
            ),
        )

        val row = WorkoutRowMapper.rows(workout).single()

        assertEquals("Грудь", row[5])
        assertEquals("Силовое", row[6])
    }

    // endregion

    // region filtering and ordering

    @Test
    fun `uncompleted sets are dropped`() {
        val workout = workoutFull(
            exercises = listOf(
                exercise(
                    name = "Присед",
                    muscleGroup = MuscleGroup.LEGS,
                    type = ExerciseType.STRENGTH,
                    position = 0,
                    sets = listOf(
                        set(setIndex = 0, weightKg = 100.0, reps = 5, isCompleted = true),
                        set(setIndex = 1, weightKg = 110.0, reps = 3, isCompleted = false),
                    ),
                ),
            ),
        )

        val rows = WorkoutRowMapper.rows(workout)

        assertEquals(1, rows.size)
        assertEquals(1, rows.single()[7]) // only the completed set (human index 1)
    }

    @Test
    fun `rows follow exercise position then set index for scrambled input`() {
        val workout = workoutFull(
            exercises = listOf(
                exercise(
                    name = "Второе",
                    muscleGroup = MuscleGroup.BACK,
                    type = ExerciseType.STRENGTH,
                    position = 1,
                    sets = listOf(
                        set(setIndex = 1, weightKg = 60.0, reps = 8, isCompleted = true),
                        set(setIndex = 0, weightKg = 50.0, reps = 10, isCompleted = true),
                    ),
                ),
                exercise(
                    name = "Первое",
                    muscleGroup = MuscleGroup.LEGS,
                    type = ExerciseType.STRENGTH,
                    position = 0,
                    sets = listOf(
                        set(setIndex = 0, weightKg = 100.0, reps = 5, isCompleted = true),
                    ),
                ),
            ),
        )

        val rows = WorkoutRowMapper.rows(workout)

        // exercise name (col 4) + human set index (col 7)
        assertEquals(
            listOf(
                "Первое" to 1,
                "Второе" to 1,
                "Второе" to 2,
            ),
            rows.map { it[4] to it[7] },
        )
    }

    @Test
    fun `workout without completed sets yields no rows`() {
        val workout = workoutFull(
            exercises = listOf(
                exercise(
                    name = "Присед",
                    muscleGroup = MuscleGroup.LEGS,
                    type = ExerciseType.STRENGTH,
                    position = 0,
                    sets = listOf(set(setIndex = 0, weightKg = 100.0, reps = 5, isCompleted = false)),
                ),
            ),
        )

        assertTrue(WorkoutRowMapper.rows(workout).isEmpty())
    }

    // endregion

    // region helpers

    private fun workoutFull(
        id: String = "w",
        name: String = "Тренировка",
        startedAt: Long = STARTED_AT,
        exercises: List<WorkoutExerciseWithSets>,
    ): WorkoutFull = WorkoutFull(
        workout = WorkoutEntity(id = id, name = name, startedAt = startedAt),
        exercises = exercises,
    )

    private fun exercise(
        name: String,
        muscleGroup: MuscleGroup,
        type: ExerciseType,
        position: Int,
        sets: List<WorkoutSetEntity>,
    ): WorkoutExerciseWithSets = WorkoutExerciseWithSets(
        workoutExercise = WorkoutExerciseEntity(id = 0, workoutId = "w", exerciseId = 0, position = position),
        exercise = ExerciseEntity(name = name, muscleGroup = muscleGroup, type = type),
        sets = sets,
    )

    private fun set(
        setIndex: Int,
        weightKg: Double? = null,
        reps: Int? = null,
        durationSec: Int? = null,
        speedKmh: Double? = null,
        inclinePct: Double? = null,
        isCompleted: Boolean,
    ): WorkoutSetEntity = WorkoutSetEntity(
        workoutExerciseId = 0,
        setIndex = setIndex,
        weightKg = weightKg,
        reps = reps,
        durationSec = durationSec,
        speedKmh = speedKmh,
        inclinePct = inclinePct,
        isCompleted = isCompleted,
    )

    private fun expectedDate(startedAt: Long): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").format(zoned(startedAt))

    private fun expectedTime(startedAt: Long): String =
        DateTimeFormatter.ofPattern("HH:mm").format(zoned(startedAt))

    private fun zoned(startedAt: Long) =
        Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault())

    // endregion

    private companion object {
        // Fixed instant; expected date/time are derived with the same system zone so the test
        // does not depend on the machine timezone.
        const val STARTED_AT = 1_700_000_000_000L
    }
}
