package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup

/** Russian label for a muscle group, shown across exercise-related screens. */
fun MuscleGroup.displayName(): String = when (this) {
    MuscleGroup.CHEST -> "Грудь"
    MuscleGroup.BACK -> "Спина"
    MuscleGroup.LEGS -> "Ноги"
    MuscleGroup.SHOULDERS -> "Плечи"
    MuscleGroup.ARMS -> "Руки"
    MuscleGroup.CORE -> "Пресс"
    MuscleGroup.CARDIO -> "Кардио"
    MuscleGroup.FULL_BODY -> "Всё тело"
}

/**
 * Russian label for a concrete muscle — подписи мышц в аналитике, легенде тепловой карты и
 * редакторе упражнения. Короткие (2 слова максимум): подписи стоят рядом с числами на графиках.
 */
fun Muscle.displayName(): String = when (this) {
    Muscle.UPPER_CHEST -> "Верх груди"
    Muscle.LOWER_CHEST -> "Низ груди"
    Muscle.FRONT_DELTS -> "Передняя дельта"
    Muscle.SIDE_DELTS -> "Средняя дельта"
    Muscle.REAR_DELTS -> "Задняя дельта"
    Muscle.ROTATOR_CUFF -> "Ротаторная манжета"
    Muscle.SERRATUS_ANTERIOR -> "Передняя зубчатая"
    Muscle.TRAPS -> "Трапеции"
    Muscle.LATS -> "Широчайшие"
    Muscle.UPPER_BACK -> "Середина спины"
    Muscle.LOWER_BACK -> "Разгибатели спины"
    Muscle.BICEPS -> "Бицепс"
    Muscle.TRICEPS -> "Трицепс"
    Muscle.FOREARMS -> "Предплечья"
    Muscle.ABS -> "Пресс"
    Muscle.OBLIQUES -> "Косые"
    Muscle.HIP_FLEXORS -> "Сгибатели бедра"
    Muscle.GLUTES -> "Ягодичные"
    Muscle.QUADS -> "Квадрицепс"
    Muscle.HAMSTRINGS -> "Бицепс бедра"
    Muscle.ADDUCTORS -> "Приводящие"
    Muscle.CALVES -> "Икры"
    Muscle.TIBIALIS_ANTERIOR -> "Передняя большеберцовая"
    Muscle.HIP_ABDUCTORS -> "Отводящие бедра"
    Muscle.NECK -> "Шея"
}

/** Russian label for an exercise type. */
fun ExerciseType.displayName(): String = when (this) {
    ExerciseType.STRENGTH -> "Силовое"
    ExerciseType.TIMED -> "На время"
    ExerciseType.CARDIO -> "Кардио"
}

/**
 * Разбирает русское имя группы мышц обратно в [MuscleGroup] (обратное к [displayName]).
 * Сравнение регистронезависимо и без пробелов по краям. Неизвестная метка → [MuscleGroup.FULL_BODY],
 * чтобы импорт из таблицы не падал на нестандартных значениях.
 */
fun muscleGroupFrom(label: String): MuscleGroup {
    val normalized = label.trim().lowercase()
    return MuscleGroup.entries.firstOrNull { it.displayName().lowercase() == normalized }
        ?: MuscleGroup.FULL_BODY
}

/**
 * Разбирает русское имя типа упражнения обратно в [ExerciseType] (обратное к [displayName]).
 * Регистронезависимо, с фоллбэком на [ExerciseType.STRENGTH] для неизвестных меток.
 */
fun exerciseTypeFrom(label: String): ExerciseType {
    val normalized = label.trim().lowercase()
    return ExerciseType.entries.firstOrNull { it.displayName().lowercase() == normalized }
        ?: ExerciseType.STRENGTH
}
