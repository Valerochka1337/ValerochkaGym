package com.valerochka1337.valerochkagym.domain.analysis

/**
 * Метаболический эквивалент (МЕТ) кардио-подхода — во что превращается «30 минут на дорожке со
 * скоростью 9 км/ч», чтобы сравнить его с рекомендацией ВОЗ.
 *
 * ВОЗ рекомендует 150–300 минут умеренной или 75–150 минут интенсивной аэробной активности в
 * неделю, что даёт примерно **500–1000 МЕТ-минут в неделю** — это и есть целевая полоса. Границы
 * интенсивности: 3.0–5.9 МЕТ — умеренная, ≥ 6.0 — интенсивная.
 *
 * Для беговой дорожки МЕТ считается по уравнениям ACSM из скорости и уклона (это точнее любой
 * таблицы), для остальных тренажёров берётся значение из Compendium of Physical Activities.
 */
object CardioMet {

  /** Целевая недельная полоса МЕТ-минут по рекомендациям ВОЗ. */
  val WHO_RANGE: ClosedFloatingPointRange<Double> = 500.0..1000.0

  private const val REST_MET = 3.5 // мл O₂/кг/мин на 1 МЕТ

  /**
   * МЕТ подхода по названию упражнения и параметрам. [speedKmh] и [inclinePct] используются только
   * там, где они осмысленны (дорожка); для прочих тренажёров — табличное значение.
   */
  fun forSet(exerciseName: String, speedKmh: Double?, inclinePct: Double?): Double {
    val name = exerciseName.lowercase()
    return when {
      name.contains("дорожк") || name.contains("беговая") -> treadmillMet(speedKmh, inclinePct)
      name.contains("велотренаж") || name.contains("велосипед") -> 7.0
      name.contains("эллипт") -> 5.0
      name.contains("гребн") -> 7.0
      name.contains("степпер") -> 8.0
      else -> 6.0
    }
  }

  /**
   * Уравнения ACSM: ходьба до 6.4 км/ч, бег выше. В разрыве 6.4–8 км/ч берётся беговое уравнение —
   * на этой скорости человек уже переходит на бег.
   *
   * `VO2` в мл/кг/мин, скорость `S` в м/мин, уклон `G` — доля (5% → 0.05).
   */
  private fun treadmillMet(speedKmh: Double?, inclinePct: Double?): Double {
    val speed = speedKmh?.takeIf { it > 0.0 } ?: return 6.0
    val metersPerMinute = speed * 1000.0 / 60.0
    val grade = (inclinePct ?: 0.0).coerceAtLeast(0.0) / 100.0
    val vo2 =
        if (speed < 6.4) {
          0.1 * metersPerMinute + 1.8 * metersPerMinute * grade + REST_MET
        } else {
          0.2 * metersPerMinute + 0.9 * metersPerMinute * grade + REST_MET
        }
    return (vo2 / REST_MET).coerceAtLeast(1.0)
  }
}
