package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import javax.inject.Inject

/**
 * Подходы «прошлого раза» для упражнения (для строки «прошлый: 30×10, 30×9» в UI) и их
 * форматирование в краткую сводку в зависимости от типа упражнения.
 */
class PreviousSetsUseCase @Inject constructor(
    private val workoutDao: WorkoutDao,
) {

    suspend operator fun invoke(exerciseId: Long): List<WorkoutSetEntity> =
        workoutDao.lastCompletedSetsForExercise(exerciseId)

    suspend operator fun invoke(key: ExerciseExecutionKey): List<WorkoutSetEntity> =
        workoutDao.lastCompletedSetsForKey(key.exerciseId, key.variantSyncId)

    /**
     * Краткая сводка подходов по типу упражнения:
     * STRENGTH — «30×10, 30×9»; TIMED — «60 сек, 45 сек»; CARDIO — «10 км/ч · 5% · 12 мин».
     * Пустые подходы пропускаются; при отсутствии данных возвращается пустая строка.
     */
    fun formatSummary(sets: List<WorkoutSetEntity>, type: ExerciseType): String =
        sets.mapNotNull { formatSet(it, type) }.joinToString(", ")
}
