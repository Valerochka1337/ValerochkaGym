package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.relation.AnalyticsSetRow
import com.valerochka1337.valerochkagym.domain.analysis.OneRepMax
import java.math.BigDecimal
import javax.inject.Inject

data class ExerciseStatisticPoint(
    val workoutId: String,
    val dateMillis: Long,
    val value: Double,
    val summary: String,
)

data class ExerciseStatisticRecord(
    val title: String,
    val value: String,
    val details: String? = null,
)

data class ExerciseStatistics(
    val chartTitle: String,
    val chartSubtitle: String,
    val chartUnit: String,
    val points: List<ExerciseStatisticPoint>,
    val lastPerformedAt: Long?,
    val lastSummary: String,
    val records: List<ExerciseStatisticRecord>,
) {
  val hasData: Boolean
    get() = lastPerformedAt != null
}

/**
 * Статистика одного упражнения по выполненным подходам завершённых тренировок.
 *
 * У разных типов упражнения разная сравнимая величина: для силовых — оценочный максимум, для
 * упражнений на время — суммарная длительность, для кардио — расчётная дистанция.
 */
class ExerciseStatisticsCalculator @Inject constructor() {

  fun calculate(type: ExerciseType, rows: List<AnalyticsSetRow>): ExerciseStatistics {
    val sessions =
        rows
            .groupBy { it.workoutId }
            .values
            .map { it.sortedBy(AnalyticsSetRow::completedAt) }
            .sortedBy { it.first().completedAt }
    val last = sessions.lastOrNull()

    return when (type) {
      ExerciseType.STRENGTH -> strength(sessions, last)
      ExerciseType.TIMED -> timed(sessions, last)
      ExerciseType.CARDIO -> cardio(sessions, last)
    }
  }

  private fun strength(
      sessions: List<List<AnalyticsSetRow>>,
      last: List<AnalyticsSetRow>?,
  ): ExerciseStatistics {
    val points =
        sessions.mapNotNull { sets ->
          val best =
              sets
                  .mapNotNull { row -> OneRepMax.epley(row.weightKg, row.reps)?.let { row to it } }
                  .maxByOrNull { it.second } ?: return@mapNotNull null
          ExerciseStatisticPoint(
              workoutId = best.first.workoutId,
              dateMillis = sets.first().completedAt,
              value = best.second,
              summary = "${number(best.first.weightKg)} кг × ${best.first.reps}",
          )
        }
    val validSets = sessions.flatten().filter { it.weightKg != null && it.reps != null }
    val bestEstimate =
        validSets
            .mapNotNull { row -> OneRepMax.epley(row.weightKg, row.reps)?.let { row to it } }
            .maxByOrNull { it.second }
    val heaviest = validSets.maxByOrNull { it.weightKg ?: 0.0 }
    val bestVolume =
        sessions.maxByOrNull { sets -> sets.sumOf { (it.weightKg ?: 0.0) * (it.reps ?: 0) } }
    val records = buildList {
      bestEstimate?.let { (row, estimate) ->
        add(
            ExerciseStatisticRecord(
                title = "Оценочный максимум",
                value = "≈ ${number(estimate)} кг",
                details = "${number(row.weightKg)} кг × ${row.reps}",
            ),
        )
      }
      heaviest?.let { row ->
        add(
            ExerciseStatisticRecord(
                title = "Максимальный вес",
                value = "${number(row.weightKg)} кг",
                details = row.reps?.let { "$it повт." },
            ),
        )
      }
      bestVolume?.let { sets ->
        val volume = sets.sumOf { (it.weightKg ?: 0.0) * (it.reps ?: 0) }
        if (volume > 0.0) {
          add(
              ExerciseStatisticRecord(
                  title = "Лучший объём тренировки",
                  value = "${number(volume)} кг",
                  details = "${sets.size} подх.",
              ),
          )
        }
      }
    }
    return ExerciseStatistics(
        chartTitle = "Динамика силы",
        chartSubtitle = "Оценка одноповторного максимума по формуле Эпли",
        chartUnit = "кг",
        points = points,
        lastPerformedAt = last?.firstOrNull()?.completedAt,
        lastSummary = last.orEmpty().mapNotNull(::strengthSummary).joinToString(", "),
        records = records,
    )
  }

  private fun timed(
      sessions: List<List<AnalyticsSetRow>>,
      last: List<AnalyticsSetRow>?,
  ): ExerciseStatistics {
    val points =
        sessions.mapNotNull { sets ->
          val totalSeconds = sets.sumOf { it.durationSec ?: 0 }
          if (totalSeconds <= 0) return@mapNotNull null
          ExerciseStatisticPoint(
              workoutId = sets.first().workoutId,
              dateMillis = sets.first().completedAt,
              value = totalSeconds / 60.0,
              summary = duration(totalSeconds),
          )
        }
    val longestSet = sessions.flatten().maxOfOrNull { it.durationSec ?: 0 } ?: 0
    val longestSession = sessions.maxOfOrNull { sets -> sets.sumOf { it.durationSec ?: 0 } } ?: 0
    return ExerciseStatistics(
        chartTitle = "Динамика времени",
        chartSubtitle = "Суммарная длительность выполненных подходов за тренировку",
        chartUnit = "мин",
        points = points,
        lastPerformedAt = last?.firstOrNull()?.completedAt,
        lastSummary =
            last
                .orEmpty()
                .mapNotNull { it.durationSec?.takeIf { value -> value > 0 }?.let(::duration) }
                .joinToString(", "),
        records =
            buildList {
              if (longestSet > 0)
                  add(ExerciseStatisticRecord("Самый долгий подход", duration(longestSet)))
              if (longestSession > 0)
                  add(ExerciseStatisticRecord("Максимум за тренировку", duration(longestSession)))
            },
    )
  }

  private fun cardio(
      sessions: List<List<AnalyticsSetRow>>,
      last: List<AnalyticsSetRow>?,
  ): ExerciseStatistics {
    val points =
        sessions.mapNotNull { sets ->
          val distance = sets.sumOf(::distanceKm)
          if (distance <= 0.0) return@mapNotNull null
          ExerciseStatisticPoint(
              workoutId = sets.first().workoutId,
              dateMillis = sets.first().completedAt,
              value = distance,
              summary = "${number(distance)} км",
          )
        }
    val all = sessions.flatten()
    val maxSpeed = all.maxOfOrNull { it.speedKmh ?: 0.0 } ?: 0.0
    val maxIncline = all.maxOfOrNull { it.inclinePct ?: 0.0 } ?: 0.0
    val longestDistance = points.maxOfOrNull { it.value } ?: 0.0
    return ExerciseStatistics(
        chartTitle = "Динамика дистанции",
        chartSubtitle = "Расчётная дистанция по скорости и длительности за тренировку",
        chartUnit = "км",
        points = points,
        lastPerformedAt = last?.firstOrNull()?.completedAt,
        lastSummary = last.orEmpty().mapNotNull(::cardioSummary).joinToString(", "),
        records =
            buildList {
              if (longestDistance > 0.0) {
                add(
                    ExerciseStatisticRecord(
                        "Самая длинная дистанция",
                        "${number(longestDistance)} км",
                    )
                )
              }
              if (maxSpeed > 0.0)
                  add(ExerciseStatisticRecord("Максимальная скорость", "${number(maxSpeed)} км/ч"))
              if (maxIncline > 0.0)
                  add(ExerciseStatisticRecord("Максимальный наклон", "${number(maxIncline)}%"))
            },
    )
  }

  private fun strengthSummary(row: AnalyticsSetRow): String? {
    val weight = row.weightKg ?: return null
    val reps = row.reps ?: return null
    return "${number(weight)}×$reps"
  }

  private fun cardioSummary(row: AnalyticsSetRow): String? {
    val parts = buildList {
      row.speedKmh?.let { add("${number(it)} км/ч") }
      row.inclinePct?.let { add("${number(it)}%") }
      row.durationSec?.takeIf { it > 0 }?.let { add(duration(it)) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
  }

  private fun distanceKm(row: AnalyticsSetRow): Double =
      (row.speedKmh ?: 0.0) * (row.durationSec ?: 0) / 3_600.0

  private fun duration(seconds: Int): String {
    val minutes = seconds / 60
    val rest = seconds % 60
    return when {
      minutes > 0 && rest > 0 -> "$minutes мин $rest сек"
      minutes > 0 -> "$minutes мин"
      else -> "$rest сек"
    }
  }

  private fun number(value: Number?): String {
    val decimal = value?.toDouble() ?: return "0"
    return BigDecimal.valueOf(decimal)
        .setScale(1, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
  }
}
