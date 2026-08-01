package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
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
