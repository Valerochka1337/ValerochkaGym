package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutExerciseWithSets
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutRowParserTest {

    // region round-trip with the exporter

    @Test
    fun `round-trips a workout exported by WorkoutRowMapper`() {
        // Времена подходов на границе минут, чтобы усечение до HH:mm давало точное равенство.
        val minute = 60_000L
        val base = 1_700_000_000_000L / minute * minute
        val workout = WorkoutFull(
            workout = WorkoutEntity(id = "w-1", name = "Ноги", startedAt = base),
            exercises = listOf(
                exercise("Присед", MuscleGroup.LEGS, ExerciseType.STRENGTH, position = 0, sets = listOf(
                    set(0, weightKg = 100.0, reps = 5, completedAt = base),
                    set(1, weightKg = 105.0, reps = 3, completedAt = base + minute),
                )),
                exercise("Планка", MuscleGroup.CORE, ExerciseType.TIMED, position = 1, sets = listOf(
                    set(0, durationSec = 60, completedAt = base + 2 * minute),
                )),
            ),
        )

        val rows = listOf(WorkoutRowMapper.HEADER_ROW) + WorkoutRowMapper.rows(workout).map(::toStrings)
        val parsed = WorkoutRowParser.parse(rows).workouts

        assertEquals(1, parsed.size)
        val pw = parsed.single()
        assertEquals("w-1", pw.id)
        assertEquals("Ноги", pw.name)
        assertEquals(base, pw.startedAt)          // минимум completedAt
        assertEquals(base + 2 * minute, pw.finishedAt) // максимум completedAt
        assertEquals(listOf("Присед", "Планка"), pw.exercises.map { it.name })
        assertEquals(listOf(0, 1), pw.exercises.map { it.position })

        val squat = pw.exercises[0]
        assertEquals(MuscleGroup.LEGS, squat.muscleGroup)
        assertEquals(ExerciseType.STRENGTH, squat.type)
        assertEquals(listOf(0, 1), squat.sets.map { it.setIndex })
        assertEquals(100.0, squat.sets[0].weightKg!!, 0.0)
        assertEquals(5, squat.sets[0].reps)
        assertEquals(base, squat.sets[0].completedAt)

        val plank = pw.exercises[1]
        assertEquals(ExerciseType.TIMED, plank.type)
        assertEquals(60, plank.sets.single().durationSec)
        assertNull(plank.sets.single().weightKg)
    }

    @Test
    fun `v9 rows retain section identity and ignore variant values`() {
        val workout = WorkoutFull(
            workout = WorkoutEntity(id = "v9", name = "Тренировка", startedAt = 1_700_000_000_000L),
            exercises = listOf(
                exercise(
                    name = "Жим",
                    muscleGroup = MuscleGroup.CHEST,
                    type = ExerciseType.STRENGTH,
                    position = 0,
                    sets = listOf(set(setIndex = 0, weightKg = 80.0, reps = 8, completedAt = 1_700_000_000_000L)),
                ),
            ),
        )
        val row = WorkoutRowMapper.rows(workout).single().map { it?.toString().orEmpty() }.toMutableList()
        row[17] = "11111111-1111-1111-1111-111111111111"
        row[18] = "Узкий хват"

        val result = WorkoutRowParser.parse(listOf(WorkoutRowMapper.HEADER_ROW, row))
        assertNull(result.fatalError)
        val parsed = result.workouts.single()

        assertEquals(row[14], parsed.exercises.single().sectionId)
        assertEquals(row[16], parsed.exercises.single().exerciseSyncId)
        assertEquals("Жим", parsed.exercises.single().name)
    }

    @Test
    fun `v9 rows reject a conflicting base tuple within one section`() {
        val workout = WorkoutFull(
            workout = WorkoutEntity(id = "v9", name = "Тренировка", startedAt = 1_700_000_000_000L),
            exercises = listOf(
                exercise(
                    name = "Жим", muscleGroup = MuscleGroup.CHEST, type = ExerciseType.STRENGTH,
                    position = 0,
                    sets = listOf(
                        set(setIndex = 0, weightKg = 80.0, reps = 8, completedAt = 1_700_000_000_000L),
                        set(setIndex = 1, weightKg = 82.5, reps = 6, completedAt = 1_700_000_060_000L),
                    ),
                ),
            ),
        )
        val rows = WorkoutRowMapper.rows(workout).map { row -> row.map { it?.toString().orEmpty() }.toMutableList() }
        rows[1][15] = "1"

        val result = WorkoutRowParser.parse(listOf(WorkoutRowMapper.HEADER_ROW) + rows)

        assertTrue(result.fatalError != null)
    }

    // endregion

    // region robustness

    @Test
    fun `skips the header row and blank rows`() {
        val rows = listOf(
            WorkoutRowMapper.HEADER_ROW.take(14),
            emptyList(),
            row(workoutId = "", exercise = "Присед"), // пустой workout_id
            row(workoutId = "w", date = "2026-01-02", time = "10:00", name = "T",
                exercise = "Присед", muscle = "Ноги", type = "Силовое", setIndex = "1", weight = "80"),
        )

        val parsed = WorkoutRowParser.parse(rows).workouts

        assertEquals(1, parsed.size)
        assertEquals("w", parsed.single().id)
    }

    @Test
    fun `rows with an id but broken time are counted as skipped not silently dropped`() {
        val rows = listOf(
            row(workoutId = "broken", date = "мусор", time = "??", name = "T",
                exercise = "Присед", muscle = "Ноги", type = "Силовое", setIndex = "1", weight = "80"),
            row(workoutId = "w", date = "2026-01-02", time = "10:00", name = "T",
                exercise = "Присед", muscle = "Ноги", type = "Силовое", setIndex = "1", weight = "80"),
        )

        val parsed = WorkoutRowParser.parse(rows)

        assertEquals(listOf("w"), parsed.workouts.map { it.id })
        assertEquals(1, parsed.skippedRows)
    }

    @Test
    fun `header and blank rows are not counted as skipped`() {
        val rows = listOf(WorkoutRowMapper.HEADER_ROW, emptyList())

        assertEquals(0, WorkoutRowParser.parse(rows).skippedRows)
    }

    @Test
    fun `parses numeric fields and leaves empty cells null`() {
        val rows = listOf(
            row(workoutId = "w", date = "2026-01-02", time = "10:00", name = "T",
                exercise = "Дорожка", muscle = "Кардио", type = "Кардио", setIndex = "1",
                weight = "", reps = "", duration = "600", speed = "10.5", incline = "5"),
        )

        val s = WorkoutRowParser.parse(rows).workouts.single().exercises.single().sets.single()

        assertNull(s.weightKg)
        assertNull(s.reps)
        assertEquals(600, s.durationSec)
        assertEquals(10.5, s.speedKmh!!, 0.0)
        assertEquals(5.0, s.inclinePct!!, 0.0)
    }

    @Test
    fun `accepts comma decimal separator`() {
        val rows = listOf(
            row(workoutId = "w", date = "2026-01-02", time = "10:00", name = "T",
                exercise = "Присед", muscle = "Ноги", type = "Силовое", setIndex = "1", weight = "100,5", reps = "5"),
        )

        assertEquals(100.5, WorkoutRowParser.parse(rows).workouts.single().exercises.single().sets.single().weightKg!!, 0.0)
    }

    @Test
    fun `unknown russian labels fall back`() {
        val rows = listOf(
            row(workoutId = "w", date = "2026-01-02", time = "10:00", name = "T",
                exercise = "Странное", muscle = "???", type = "???", setIndex = "1", weight = "10", reps = "1"),
        )

        val ex = WorkoutRowParser.parse(rows).workouts.single().exercises.single()
        assertEquals(MuscleGroup.FULL_BODY, ex.muscleGroup)
        assertEquals(ExerciseType.STRENGTH, ex.type)
    }

    @Test
    fun `groups rows into two workouts preserving order`() {
        val rows = listOf(
            row(workoutId = "a", date = "2026-01-02", time = "10:00", name = "A",
                exercise = "Присед", muscle = "Ноги", type = "Силовое", setIndex = "1", weight = "80"),
            row(workoutId = "b", date = "2026-01-03", time = "11:00", name = "B",
                exercise = "Жим", muscle = "Грудь", type = "Силовое", setIndex = "1", weight = "60"),
        )

        assertEquals(listOf("a", "b"), WorkoutRowParser.parse(rows).workouts.map { it.id })
    }

    @Test
    fun `empty input yields no workouts`() {
        assertTrue(WorkoutRowParser.parse(emptyList()).workouts.isEmpty())
    }

    // endregion

    // region helpers

    private fun toStrings(cells: List<Any?>): List<String> = cells.map { it?.toString() ?: "" }

    /** Строка листа в порядке HEADER_ROW; незаданные поля — пустые. */
    private fun row(
        workoutId: String,
        date: String = "",
        time: String = "",
        name: String = "",
        exercise: String = "",
        muscle: String = "",
        type: String = "",
        setIndex: String = "",
        weight: String = "",
        reps: String = "",
        duration: String = "",
        speed: String = "",
        incline: String = "",
        volume: String = "",
    ): List<String> = listOf(
        workoutId, date, time, name, exercise, muscle, type, setIndex,
        weight, reps, duration, speed, incline, volume,
    )

    private fun exercise(
        name: String,
        muscleGroup: MuscleGroup,
        type: ExerciseType,
        position: Int,
        sets: List<WorkoutSetEntity>,
    ): WorkoutExerciseWithSets = WorkoutExerciseWithSets(
        workoutExercise = WorkoutExerciseEntity(id = 0, workoutId = "w-1", exerciseId = 0, position = position),
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
        completedAt: Long,
    ): WorkoutSetEntity = WorkoutSetEntity(
        workoutExerciseId = 0,
        setIndex = setIndex,
        weightKg = weightKg,
        reps = reps,
        durationSec = durationSec,
        speedKmh = speedKmh,
        inclinePct = inclinePct,
        isCompleted = true,
        completedAt = completedAt,
    )

    // endregion
}
