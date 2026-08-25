package com.valerochka1337.valerochkagym.domain.analysis

import com.valerochka1337.valerochkagym.data.db.entity.Muscle

/**
 * Ориентиры недельного объёма для одной мышцы, в подходах в неделю.
 *
 * Терминология — из системы объёмных ориентиров Israetel / Renaissance Periodization, которая
 * сейчас де-факто стандарт планирования гипертрофии:
 *
 * | Ориентир | Что значит |
 * |---|---|
 * | [mev] | Minimum Effective Volume — ниже роста практически нет, объём только поддерживает |
 * | [mavLow]..[mavHigh] | Maximum Adaptive Volume — «рабочий коридор», где идёт основной рост |
 * | [mrv] | Maximum Recoverable Volume — выше этого организм не успевает восстанавливаться |
 *
 * Числа согласуются с мета-анализами дозозависимости (Schoenfeld и др.): для большинства мышц
 * рост продолжается примерно до 10–20 подходов в неделю, а дальше отдача падает. Мелкие мышцы,
 * получающие много косвенной работы (передняя дельта, трицепс, разгибатели спины), имеют более
 * низкий потолок именно из-за этого.
 *
 * Подходы считаются «эффективными», то есть с долей вовлечения:
 * см. [com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad].
 */
data class MuscleLandmarks(
    val mev: Double,
    val mavLow: Double,
    val mavHigh: Double,
    val mrv: Double,
)

/** Зона недельного объёма мышцы — то, что закрашивается на карте тела и подписывается словом. */
enum class VolumeZone {
    /** Ноль подходов за неделю. */
    NONE,

    /** Ниже MEV: нагрузка есть, но для роста её мало. */
    BELOW_MEV,

    /** MEV..MAV: рабочий минимум, рост идёт. */
    MAINTENANCE,

    /** Внутри адаптивного коридора — целевое состояние. */
    OPTIMAL,

    /** Выше MRV: объём выше восстанавливаемого. */
    EXCESSIVE,
}

/**
 * Ориентиры по мышцам (MEV / MAV / MRV подходов в неделю) по таблицам Israetel / RP.
 *
 * Это экспертное мнение, а не РКИ: сама RP публикует расходящиеся числа для одной и той же
 * мышцы. Поэтому в интерфейсе они рисуются как мягкий ориентир, а основной опорой служит
 * мета-аналитическая полоса [META_ANALYTIC_RANGE], одинаковая для всех мышц.
 */
val muscleLandmarks: Map<Muscle, MuscleLandmarks> = mapOf(
    Muscle.CHEST to MuscleLandmarks(mev = 8.0, mavLow = 12.0, mavHigh = 20.0, mrv = 22.0),
    Muscle.FRONT_DELTS to MuscleLandmarks(mev = 0.0, mavLow = 6.0, mavHigh = 12.0, mrv = 16.0),
    Muscle.SIDE_DELTS to MuscleLandmarks(mev = 8.0, mavLow = 16.0, mavHigh = 22.0, mrv = 26.0),
    Muscle.REAR_DELTS to MuscleLandmarks(mev = 6.0, mavLow = 12.0, mavHigh = 20.0, mrv = 26.0),
    Muscle.TRAPS to MuscleLandmarks(mev = 4.0, mavLow = 12.0, mavHigh = 20.0, mrv = 26.0),
    Muscle.LATS to MuscleLandmarks(mev = 10.0, mavLow = 14.0, mavHigh = 22.0, mrv = 25.0),
    Muscle.UPPER_BACK to MuscleLandmarks(mev = 10.0, mavLow = 14.0, mavHigh = 22.0, mrv = 25.0),
    Muscle.LOWER_BACK to MuscleLandmarks(mev = 2.0, mavLow = 6.0, mavHigh = 12.0, mrv = 16.0),
    Muscle.BICEPS to MuscleLandmarks(mev = 8.0, mavLow = 14.0, mavHigh = 20.0, mrv = 26.0),
    Muscle.TRICEPS to MuscleLandmarks(mev = 6.0, mavLow = 10.0, mavHigh = 14.0, mrv = 18.0),
    Muscle.FOREARMS to MuscleLandmarks(mev = 2.0, mavLow = 8.0, mavHigh = 16.0, mrv = 20.0),
    Muscle.ABS to MuscleLandmarks(mev = 0.0, mavLow = 16.0, mavHigh = 20.0, mrv = 25.0),
    Muscle.OBLIQUES to MuscleLandmarks(mev = 0.0, mavLow = 8.0, mavHigh = 16.0, mrv = 20.0),
    Muscle.GLUTES to MuscleLandmarks(mev = 4.0, mavLow = 8.0, mavHigh = 16.0, mrv = 20.0),
    Muscle.QUADS to MuscleLandmarks(mev = 8.0, mavLow = 12.0, mavHigh = 18.0, mrv = 20.0),
    Muscle.HAMSTRINGS to MuscleLandmarks(mev = 6.0, mavLow = 10.0, mavHigh = 16.0, mrv = 20.0),
    Muscle.ADDUCTORS to MuscleLandmarks(mev = 0.0, mavLow = 6.0, mavHigh = 12.0, mrv = 16.0),
    Muscle.CALVES to MuscleLandmarks(mev = 8.0, mavLow = 12.0, mavHigh = 16.0, mrv = 20.0),
)

/**
 * Опорный диапазон из мета-анализов дозозависимости (Schoenfeld 2017, Baz-Valle 2022,
 * Pelland 2026): примерно 10–20 подходов в неделю на мышцу — там, где рост идёт у большинства.
 * Общий для всех мышц и потому подписывается один раз на карточку.
 */
val META_ANALYTIC_RANGE: ClosedFloatingPointRange<Double> = 10.0..20.0

/** Ориентиры мышцы; для новых значений enum — умеренный «средний» набор вместо падения. */
fun Muscle.landmarks(): MuscleLandmarks =
    muscleLandmarks[this] ?: MuscleLandmarks(mev = 6.0, mavLow = 10.0, mavHigh = 16.0, mrv = 20.0)

/** Зона, в которую попадает [weeklySets] эффективных подходов этой мышцы. */
fun Muscle.zoneFor(weeklySets: Double): VolumeZone {
    val landmarks = landmarks()
    return when {
        weeklySets <= 0.0 -> VolumeZone.NONE
        weeklySets < landmarks.mev -> VolumeZone.BELOW_MEV
        weeklySets < landmarks.mavLow -> VolumeZone.MAINTENANCE
        weeklySets <= landmarks.mrv -> VolumeZone.OPTIMAL
        else -> VolumeZone.EXCESSIVE
    }
}

/**
 * Вес одного подхода для мышцы по её доле вовлечения [contribution].
 *
 * Заметная нагрузка считается непрерывно: 100 даёт один эффективный подход, 60 — 0.6, 25 — 0.25.
 * Так сохраняется относительность общей шкалы между упражнениями. Значения ниже 25 отсекаются:
 * они описывают стабилизацию и не должны раздувать недельный объём.
 */
fun setWeightFor(contribution: Int): Double =
    if (contribution >= 25) contribution.coerceAtMost(100) / 100.0 else 0.0
