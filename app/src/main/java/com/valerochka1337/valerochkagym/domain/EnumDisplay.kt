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
