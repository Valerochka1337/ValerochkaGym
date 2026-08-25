package com.valerochka1337.valerochkagym.data.db

import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad

/**
 * Фоллбэк-карта вовлечения по крупной группе мышц: используется, когда точной карты нет —
 * для своих упражнений, созданных без разметки по телу, и для упражнений, приехавших импортом
 * из Google Sheets (там есть только колонка `muscle_group`).
 *
 * Числа — «типичный представитель» группы (жим лёжа для груди, тяга для спины, приседания для
 * ног). Шкала глобальная: кардио и упражнения на выносливость не получают искусственные 100%.
 * Точные карты
 * встроенного каталога живут в [seedExerciseMuscles].
 */
fun MuscleGroup.defaultMuscleLoads(): List<MuscleLoad> = when (this) {
    MuscleGroup.CHEST -> listOf(
        MuscleLoad(Muscle.CHEST, 100),
        MuscleLoad(Muscle.TRICEPS, 55),
        MuscleLoad(Muscle.FRONT_DELTS, 50),
    )

    MuscleGroup.BACK -> listOf(
        MuscleLoad(Muscle.LATS, 100),
        MuscleLoad(Muscle.UPPER_BACK, 70),
        MuscleLoad(Muscle.BICEPS, 50),
        MuscleLoad(Muscle.REAR_DELTS, 40),
        MuscleLoad(Muscle.FOREARMS, 30),
        MuscleLoad(Muscle.LOWER_BACK, 25),
    )

    MuscleGroup.LEGS -> listOf(
        MuscleLoad(Muscle.QUADS, 100),
        MuscleLoad(Muscle.GLUTES, 75),
        MuscleLoad(Muscle.HAMSTRINGS, 45),
        MuscleLoad(Muscle.ADDUCTORS, 40),
        MuscleLoad(Muscle.LOWER_BACK, 25),
        MuscleLoad(Muscle.CALVES, 20),
    )

    MuscleGroup.SHOULDERS -> listOf(
        MuscleLoad(Muscle.FRONT_DELTS, 100),
        MuscleLoad(Muscle.SIDE_DELTS, 75),
        MuscleLoad(Muscle.TRICEPS, 40),
        MuscleLoad(Muscle.TRAPS, 35),
        MuscleLoad(Muscle.REAR_DELTS, 25),
    )

    MuscleGroup.ARMS -> listOf(
        MuscleLoad(Muscle.BICEPS, 100),
        MuscleLoad(Muscle.TRICEPS, 100),
        MuscleLoad(Muscle.FOREARMS, 35),
    )

    MuscleGroup.CORE -> listOf(
        MuscleLoad(Muscle.ABS, 100),
        MuscleLoad(Muscle.OBLIQUES, 60),
        MuscleLoad(Muscle.LOWER_BACK, 20),
    )

    MuscleGroup.CARDIO -> listOf(
        MuscleLoad(Muscle.QUADS, 20),
        MuscleLoad(Muscle.CALVES, 15),
        MuscleLoad(Muscle.HAMSTRINGS, 15),
        MuscleLoad(Muscle.GLUTES, 15),
    )

    MuscleGroup.FULL_BODY -> listOf(
        MuscleLoad(Muscle.QUADS, 70),
        MuscleLoad(Muscle.GLUTES, 60),
        MuscleLoad(Muscle.ABS, 45),
        MuscleLoad(Muscle.HAMSTRINGS, 40),
        MuscleLoad(Muscle.FRONT_DELTS, 35),
        MuscleLoad(Muscle.LATS, 30),
        MuscleLoad(Muscle.CALVES, 25),
        MuscleLoad(Muscle.TRICEPS, 25),
    )
}
