package com.valerochka1337.valerochkagym.data.db.relation

/**
 * Суммарный тоннаж одной завершённой тренировки. Проекция для списка истории: позволяет показать
 * объём по каждой тренировке одним агрегатным запросом, не загружая полные деревья [WorkoutFull].
 */
data class WorkoutVolume(
    val workoutId: String,
    val volume: Double,
)
