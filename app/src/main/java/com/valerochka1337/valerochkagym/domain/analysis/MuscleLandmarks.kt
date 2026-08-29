package com.valerochka1337.valerochkagym.domain.analysis

import com.valerochka1337.valerochkagym.data.db.entity.Muscle

/**
 * Общая шкала эффективных подходов в неделю для цели «рост мышц».
 *
 * Это не индивидуальные физиологические пороги и не медицинская оценка. Больший объём в среднем
 * связан с большей гипертрофией, но отдача снижается, а единого универсального оптимума или
 * потолка восстановления данные не задают. Шкала помогает читать собственную историю, а не
 * выносит вердикт восстановлению.
 *
 * Основание: https://pubmed.ncbi.nlm.nih.gov/41343037/
 */
enum class VolumeZone {
    /** 0–2 эффективных подхода: малый объём. */
    LOW,

    /** Больше 2, но меньше 5: базовый объём. */
    BASE,

    /** 5–<10: рабочий объём. */
    WORKING,

    /** 10 и больше: ориентир для роста, не верхний лимит. */
    GROWTH_GUIDE,
}

/** Границы единой UX-шкалы (эффективные подходы в неделю). */
object HypertrophyVolumeGuide {
    const val LOW_MAX = 2.0
    const val BASE_MAX = 5.0
    const val WORKING_MAX = 10.0
}

/** Зона единой шкалы, в которую попадает [weeklySets]. */
fun volumeZoneFor(weeklySets: Double): VolumeZone = when {
    weeklySets <= HypertrophyVolumeGuide.LOW_MAX -> VolumeZone.LOW
    weeklySets < HypertrophyVolumeGuide.BASE_MAX -> VolumeZone.BASE
    weeklySets < HypertrophyVolumeGuide.WORKING_MAX -> VolumeZone.WORKING
    else -> VolumeZone.GROWTH_GUIDE
}

/** Удобный вызов для модели мышцы; шкала одинакова для всех мышц. */
fun Muscle.zoneFor(weeklySets: Double): VolumeZone =
    volumeZoneFor(weeklySets)

/**
 * Вклад одного рабочего подхода в мышцу по доле вовлечения [contribution].
 *
 * Шкала дискретная: прямая нагрузка (≥60) даёт один эффективный подход, косвенная (25–59) —
 * половину, стабилизация ниже 25 не учитывается. Это практическая оценка для карты упражнений;
 * приложение не знает близость подхода к отказу, технику, сон или восстановление.
 */
fun setWeightFor(contribution: Int): Double = when {
    contribution >= 60 -> 1.0
    contribution >= 25 -> 0.5
    else -> 0.0
}
