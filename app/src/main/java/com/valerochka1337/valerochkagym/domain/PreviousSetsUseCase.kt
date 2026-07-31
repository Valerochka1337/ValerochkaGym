package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import java.math.BigDecimal
import javax.inject.Inject

/**
 * Подходы «прошлого раза» для упражнения (для строки «прошлый: 30×10, 30×9» в UI) и их
 * форматирование в краткую сводку в зависимости от типа упражнения. Форматирование
 * локаль-независимо, вес — без хвостовых нулей.
 */
class PreviousSetsUseCase @Inject constructor(
    private val workoutDao: WorkoutDao,
) {

    suspend operator fun invoke(exerciseId: Long): List<WorkoutSetEntity> =
        workoutDao.lastCompletedSetsForExercise(exerciseId)

    /**
     * Краткая сводка подходов по типу упражнения:
     * STRENGTH — «30×10, 30×9»; TIMED — «60 сек, 45 сек»; CARDIO — «10 км/ч · 5%, 12 мин».
     * Пустые подходы пропускаются; при отсутствии данных возвращается пустая строка.
     */
    fun formatSummary(sets: List<WorkoutSetEntity>, type: ExerciseType): String =
        sets.mapNotNull { formatSet(it, type) }.joinToString(", ")

    private fun formatSet(set: WorkoutSetEntity, type: ExerciseType): String? = when (type) {
        ExerciseType.STRENGTH -> {
            val weight = set.weightKg?.let(::formatNumber)
            val reps = set.reps?.toString()
            when {
                weight != null && reps != null -> "$weight×$reps"
                weight != null -> weight
                reps != null -> reps
                else -> null
            }
        }

        ExerciseType.TIMED -> set.durationSec?.let { "$it сек" }

        ExerciseType.CARDIO -> {
            val parts = buildList {
                set.speedKmh?.let { add("${formatNumber(it)} км/ч") }
                set.inclinePct?.let { add("${formatNumber(it)}%") }
                set.durationSec?.let { add("${it / 60} мин") }
            }
            parts.joinToString(" · ").ifEmpty { null }
        }
    }
}

/** Число без хвостовых нулей, локаль-независимо: 30, 32.5. */
private fun formatNumber(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
