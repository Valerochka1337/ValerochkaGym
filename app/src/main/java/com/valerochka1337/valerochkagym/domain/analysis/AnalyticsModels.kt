package com.valerochka1337.valerochkagym.domain.analysis

import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import java.time.LocalDate

/**
 * Окно, за которое считается аналитика. Метрики, привязанные к конкретному сроку
 * (соотношение острой и хронической нагрузки, рекорды), окно игнорируют — см. их описания.
 */
enum class AnalysisPeriod(val weeks: Int?) {
    /** Последние 4 недели — «что происходит сейчас». */
    WEEKS_4(4),

    /** Последние 12 недель — типичный мезоцикл, окно по умолчанию. */
    WEEKS_12(12),

    /** Последний год — сезонность и долгий тренд. */
    YEAR(52),

    /** Вся история. */
    ALL(null),
}

/** Точка недельного графика. [partial] — текущая неделя, она ещё не закончилась. */
data class WeeklyPoint(
    val weekStart: LocalDate,
    val label: String,
    val hardSets: Double,
    val tonnageKg: Double,
    val sessions: Int,
    val partial: Boolean,
)

/**
 * Недельная нагрузка на одну мышцу.
 *
 * [weeklySets] — среднее число эффективных подходов в неделю за выбранное окно (а не сумма):
 * только так 4 недели и год сравнимы между собой и с ориентирами [MuscleLandmarks].
 */
data class MuscleLoadSummary(
    val muscle: Muscle,
    val weeklySets: Double,
    val totalSets: Double,
    val tonnageKg: Double,
    val zone: VolumeZone,
    val sessionsPerWeek: Double,
    val daysSinceLast: Int?,
    val topExercises: List<String>,
)

/** Одна тренировка на графике прогресса упражнения. */
data class ExerciseSessionPoint(
    val workoutId: String,
    val dateMillis: Long,
    val bestE1rm: Double,
    val bestWeightKg: Double,
    val bestWeightReps: Int,
    val tonnageKg: Double,
    val sets: Int,
)

/** Лучший вес, поднятый хотя бы на [reps] повторений — точка «силовой кривой». */
data class RepMaxPoint(
    val reps: Int,
    val weightKg: Double,
    val dateMillis: Long,
)

/** Вердикт по тренду силы — то, ради чего человек и смотрит на график. */
enum class TrendVerdict {
    /** Меньше 5 тренировок: наклон по такой выборке — шум. */
    NOT_ENOUGH_DATA,

    /** Рост за месяц больше порога заметности. */
    GROWING,

    /** Изменение меньше порога: плато. */
    STALLED,

    /** Устойчивое снижение. */
    REGRESSING,
}

/**
 * Прогресс одного упражнения. [trendPercentPerMonth] — наклон линейной регрессии e1RM по
 * времени, приведённый к процентам от среднего за месяц: «+2%/мес» читается, а «+0.7 кг/день»
 * нет. Считается только при ≥ 3 тренировках, вердикт — при ≥ 5.
 */
data class ExerciseProgress(
    val exerciseId: Long,
    val name: String,
    val points: List<ExerciseSessionPoint>,
    val repMaxes: List<RepMaxPoint>,
    val bestE1rm: Double?,
    val trendPercentPerMonth: Double?,
    val trendKgPerMonth: Double?,
    val verdict: TrendVerdict,
)

/**
 * Соотношение острой (7 дней) и хронической (среднее за 28 дней) нагрузки в эффективных
 * подходах. Классический ACWR: коридор 0.8..1.3 — управляемый рост нагрузки, выше 1.5 —
 * резкий скачок, который в спортивной литературе связывают с повышенным риском травмы.
 *
 * [hasEnoughData] = false, пока история короче 28 дней: до этого «хроническая» нагрузка
 * недооценена и отношение всегда завышено. В этом случае показатель не показывают.
 */
data class WorkloadRatio(
    val acuteSets: Double,
    val chronicWeeklySets: Double,
    val ratio: Double?,
    val hasEnoughData: Boolean,
)

/**
 * Баланс двух наборов мышц по эффективным подходам за окно. [ratio] = [leftSets] / [rightSets];
 * `null`, когда правая часть пуста (делить не на что).
 */
data class BalanceRatio(
    val id: BalanceId,
    val leftSets: Double,
    val rightSets: Double,
    val ratio: Double?,
    val targetLow: Double,
    val targetHigh: Double,
) {
    val inTarget: Boolean get() = ratio != null && ratio in targetLow..targetHigh
}

/** Какие именно балансы считаем; подписи живут в UI. */
enum class BalanceId { PUSH_PULL, ANTERIOR_POSTERIOR, UPPER_LOWER, QUAD_HAMSTRING }

/** Личный рекорд по упражнению за всю историю (окно на него не влияет). */
data class ExerciseRecord(
    val exerciseId: Long,
    val name: String,
    val bestE1rm: Double,
    val weightKg: Double,
    val reps: Int,
    val dateMillis: Long,
)

/**
 * Полный отчёт вкладки «Анализы» за одно окно. Пустая история — [hasData] = false, все списки
 * пустые: экран показывает объяснение вместо графиков, а не нули.
 */
data class AnalyticsReport(
    val hasData: Boolean,
    val period: AnalysisPeriod,
    val periodWeeks: Double,
    val sessions: Int,
    val sessionsPerWeek: Double,
    val totalTonnageKg: Double,
    val totalHardSets: Double,
    val avgSessionMinutes: Int,
    val cardioMinutes: Int,
    val aerobicMetMinutesPerWeek: Double,
    val weeklyPoints: List<WeeklyPoint>,
    val muscleLoads: List<MuscleLoadSummary>,
    val exercises: List<ExerciseProgress>,
    val workload: WorkloadRatio,
    val balances: List<BalanceRatio>,
    val records: List<ExerciseRecord>,
    val streakWeeks: Int,
    val daysSinceLast: Int?,
) {
    companion object {
        fun empty(period: AnalysisPeriod): AnalyticsReport = AnalyticsReport(
            hasData = false,
            period = period,
            periodWeeks = 1.0,
            sessions = 0,
            sessionsPerWeek = 0.0,
            totalTonnageKg = 0.0,
            totalHardSets = 0.0,
            avgSessionMinutes = 0,
            cardioMinutes = 0,
            aerobicMetMinutesPerWeek = 0.0,
            weeklyPoints = emptyList(),
            muscleLoads = emptyList(),
            exercises = emptyList(),
            workload = WorkloadRatio(0.0, 0.0, null, hasEnoughData = false),
            balances = emptyList(),
            records = emptyList(),
            streakWeeks = 0,
            daysSinceLast = null,
        )
    }
}
