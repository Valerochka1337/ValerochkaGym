package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Краткая запись подхода: одна строка на экран, уведомление и плашку. */
class SetFormattingTest {

    private fun set(
        weightKg: Double? = null,
        reps: Int? = null,
        durationSec: Int? = null,
        speedKmh: Double? = null,
        inclinePct: Double? = null,
    ) = WorkoutSetEntity(
        workoutExerciseId = 1L,
        setIndex = 0,
        weightKg = weightKg,
        reps = reps,
        durationSec = durationSec,
        speedKmh = speedKmh,
        inclinePct = inclinePct,
    )

    @Test
    fun `strength set reads as weight times reps without trailing zeros`() {
        assertEquals("30×10", formatSet(set(weightKg = 30.0, reps = 10), ExerciseType.STRENGTH))
        assertEquals("32.5×8", formatSet(set(weightKg = 32.5, reps = 8), ExerciseType.STRENGTH))
    }

    @Test
    fun `strength set with only one value shows just that value`() {
        assertEquals("60", formatSet(set(weightKg = 60.0), ExerciseType.STRENGTH))
        assertEquals("12", formatSet(set(reps = 12), ExerciseType.STRENGTH))
        assertNull(formatSet(set(), ExerciseType.STRENGTH))
    }

    @Test
    fun `timed set is seconds or nothing`() {
        assertEquals("60 сек", formatSet(set(durationSec = 60), ExerciseType.TIMED))
        assertNull(formatSet(set(), ExerciseType.TIMED))
    }

    @Test
    fun `cardio set joins speed incline and whole minutes`() {
        assertEquals(
            "10 км/ч · 5% · 12 мин",
            formatSet(set(durationSec = 720, speedKmh = 10.0, inclinePct = 5.0), ExerciseType.CARDIO),
        )
        assertEquals("8.5 км/ч", formatSet(set(speedKmh = 8.5), ExerciseType.CARDIO))
        assertNull(formatSet(set(), ExerciseType.CARDIO))
    }
}
