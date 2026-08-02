package com.valerochka1337.valerochkagym.data.db.entity

/**
 * Конкретная мышца (мышечная группа второго уровня) — единица учёта нагрузки в аналитике.
 *
 * [MuscleGroup] остаётся крупной категорией упражнения (фильтры библиотеки, колонка
 * `muscle_group` в выгрузке Sheets), а [Muscle] описывает, что именно упражнение нагружает:
 * жим лёжа — это грудь + трицепс + передняя дельта, а не «грудь». Карта вовлечения хранится
 * в `exercise_muscles` ([ExerciseMuscleEntity]) и питает тепловую карту тела и подсчёт
 * эффективных подходов на мышцу.
 *
 * Порядок значений — анатомический (сверху вниз, спереди назад): он же порядок вывода
 * в списках и легенде.
 */
enum class Muscle {
    CHEST,
    FRONT_DELTS,
    SIDE_DELTS,
    REAR_DELTS,
    TRAPS,
    LATS,
    UPPER_BACK,
    LOWER_BACK,
    BICEPS,
    TRICEPS,
    FOREARMS,
    ABS,
    OBLIQUES,
    GLUTES,
    QUADS,
    HAMSTRINGS,
    ADDUCTORS,
    CALVES,
}

/**
 * Вовлечение [muscle] в упражнение: [contribution] — доля 0..100, где 100 — целевая мышца
 * движения, 55..85 — сильный синергист, 25..50 — заметное участие, 10..20 — стабилизатор.
 *
 * Та же доля используется как вес «эффективного подхода»: подход упражнения даёт мышце
 * `contribution / 100` подхода (прямая работа — 1.0, косвенная — доля), что соответствует
 * практике учёта косвенных подходов при подсчёте недельного объёма.
 */
data class MuscleLoad(
    val muscle: Muscle,
    val contribution: Int,
)

/** Крупная категория, к которой относится мышца — для группировки в UI и фоллбэков. */
fun Muscle.group(): MuscleGroup = when (this) {
    Muscle.CHEST -> MuscleGroup.CHEST
    Muscle.FRONT_DELTS, Muscle.SIDE_DELTS, Muscle.REAR_DELTS -> MuscleGroup.SHOULDERS
    Muscle.TRAPS, Muscle.LATS, Muscle.UPPER_BACK, Muscle.LOWER_BACK -> MuscleGroup.BACK
    Muscle.BICEPS, Muscle.TRICEPS, Muscle.FOREARMS -> MuscleGroup.ARMS
    Muscle.ABS, Muscle.OBLIQUES -> MuscleGroup.CORE
    Muscle.GLUTES, Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.ADDUCTORS, Muscle.CALVES -> MuscleGroup.LEGS
}
