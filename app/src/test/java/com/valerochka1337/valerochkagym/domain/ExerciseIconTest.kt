package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.R
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.seedExercises
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [iconResFor]. The mapping is a pure keyword function over the exercise
 * name with a muscle-group fallback, so no Room/Robolectric is needed — just int compares
 * against the generated `R.drawable` ids.
 */
class ExerciseIconTest {

    private fun ex(
        name: String,
        group: MuscleGroup = MuscleGroup.CHEST,
        type: ExerciseType = ExerciseType.STRENGTH,
    ) = ExerciseEntity(name = name, muscleGroup = group, type = type)

    @Test
    fun `barbell keywords map to barbell icon`() {
        assertEquals(R.drawable.ic_ex_barbell, iconResFor(ex("Жим штанги лёжа")))
        assertEquals(R.drawable.ic_ex_barbell, iconResFor(ex("Становая тяга", MuscleGroup.BACK)))
        assertEquals(R.drawable.ic_ex_barbell, iconResFor(ex("Румынская тяга", MuscleGroup.LEGS)))
        assertEquals(R.drawable.ic_ex_barbell, iconResFor(ex("Тяга Т-грифа", MuscleGroup.BACK)))
        assertEquals(R.drawable.ic_ex_barbell, iconResFor(ex("Трастеры со штангой", MuscleGroup.FULL_BODY)))
    }

    @Test
    fun `dumbbell keywords map to dumbbell icon`() {
        assertEquals(R.drawable.ic_ex_dumbbell, iconResFor(ex("Жим гантелей лёжа")))
        assertEquals(R.drawable.ic_ex_dumbbell, iconResFor(ex("Молотки с гантелями", MuscleGroup.ARMS)))
    }

    @Test
    fun `machine keywords map to machine icon`() {
        assertEquals(R.drawable.ic_ex_machine, iconResFor(ex("Жим в тренажёре Хаммер")))
        assertEquals(R.drawable.ic_ex_machine, iconResFor(ex("Приседания в Смите", MuscleGroup.LEGS)))
        assertEquals(R.drawable.ic_ex_machine, iconResFor(ex("Гакк-приседания", MuscleGroup.LEGS)))
        assertEquals(R.drawable.ic_ex_machine, iconResFor(ex("Жим ногами", MuscleGroup.LEGS)))
    }

    @Test
    fun `cable keywords map to cable icon`() {
        assertEquals(R.drawable.ic_ex_cable, iconResFor(ex("Тяга верхнего блока", MuscleGroup.BACK)))
        assertEquals(R.drawable.ic_ex_cable, iconResFor(ex("Сведение рук в кроссовере")))
    }

    @Test
    fun `kettlebell keyword maps to kettlebell icon`() {
        assertEquals(R.drawable.ic_ex_kettlebell, iconResFor(ex("Махи гирей", MuscleGroup.FULL_BODY)))
    }

    @Test
    fun `bodyweight and core keywords map to their icons`() {
        assertEquals(R.drawable.ic_ex_bodyweight, iconResFor(ex("Подтягивания", MuscleGroup.BACK)))
        assertEquals(R.drawable.ic_ex_bodyweight, iconResFor(ex("Отжимания от пола")))
        assertEquals(R.drawable.ic_ex_core, iconResFor(ex("Планка", MuscleGroup.CORE, ExerciseType.TIMED)))
        assertEquals(R.drawable.ic_ex_core, iconResFor(ex("Скручивания", MuscleGroup.CORE)))
    }

    @Test
    fun `cardio machines map to their specific icons`() {
        assertEquals(R.drawable.ic_ex_treadmill, iconResFor(ex("Беговая дорожка", MuscleGroup.CARDIO, ExerciseType.CARDIO)))
        assertEquals(R.drawable.ic_ex_elliptical, iconResFor(ex("Эллиптический тренажёр", MuscleGroup.CARDIO, ExerciseType.CARDIO)))
        assertEquals(R.drawable.ic_ex_rower, iconResFor(ex("Гребной тренажёр", MuscleGroup.CARDIO, ExerciseType.CARDIO)))
        assertEquals(R.drawable.ic_ex_stepper, iconResFor(ex("Степпер", MuscleGroup.CARDIO, ExerciseType.CARDIO)))
        assertEquals(R.drawable.ic_ex_box, iconResFor(ex("Прыжки на тумбу", MuscleGroup.FULL_BODY)))
    }

    /** «Велосипед» — упражнение на пресс, «Велотренажёр» — кардио-байк. Не перепутать. */
    @Test
    fun `bicycle crunch is core, exercise bike is bike`() {
        assertEquals(R.drawable.ic_ex_core, iconResFor(ex("Велосипед", MuscleGroup.CORE)))
        assertEquals(R.drawable.ic_ex_bike, iconResFor(ex("Велотренажёр", MuscleGroup.CARDIO, ExerciseType.CARDIO)))
    }

    @Test
    fun `unknown custom exercise falls back by group`() {
        assertEquals(R.drawable.ic_ex_dumbbell, iconResFor(ex("Моё упражнение", MuscleGroup.CHEST)))
        assertEquals(R.drawable.ic_ex_core, iconResFor(ex("Моё упражнение", MuscleGroup.CORE)))
        assertEquals(
            R.drawable.ic_ex_treadmill,
            iconResFor(ex("Моё кардио", MuscleGroup.CARDIO, ExerciseType.CARDIO)),
        )
    }

    /** Каждое встроенное упражнение должно получать конкретную иконку, а кардио-группа — кардио-иконку. */
    @Test
    fun `every seed exercise resolves to a known icon`() {
        val cardioIcons = setOf(
            R.drawable.ic_ex_treadmill,
            R.drawable.ic_ex_bike,
            R.drawable.ic_ex_elliptical,
            R.drawable.ic_ex_rower,
            R.drawable.ic_ex_stepper,
        )
        for (exercise in seedExercises) {
            val icon = iconResFor(exercise)
            assertTrue("no icon for ${exercise.name}", icon != 0)
            if (exercise.muscleGroup == MuscleGroup.CARDIO) {
                assertTrue("cardio icon expected for ${exercise.name}", icon in cardioIcons)
            }
        }
    }
}
