package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import java.math.BigDecimal

/**
 * Краткая запись одного подхода по типу упражнения: STRENGTH — «30×10», TIMED — «60 сек», CARDIO —
 * «10 км/ч · 5% · 12 мин». null — когда заполнять нечем.
 *
 * Локаль-независимо, вес без хвостовых нулей. Отдельная чистая функция (а не метод
 * [PreviousSetsUseCase]), потому что этой же строкой подписаны сводка «прошлый: …» на экране,
 * уведомление активной тренировки и плашка «тренировка идёт» — форматирование должно быть одно на
 * все места, а тянуть ради него DAO незачем.
 */
fun formatSet(set: WorkoutSetEntity, type: ExerciseType): String? =
    when (type) {
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

/** Число без хвостовых нулей, локаль-независимо: 30, 32.5. */
private fun formatNumber(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
