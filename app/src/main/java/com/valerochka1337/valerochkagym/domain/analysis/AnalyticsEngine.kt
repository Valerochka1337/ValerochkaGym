package com.valerochka1337.valerochkagym.domain.analysis

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.relation.AnalyticsSetRow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlin.math.roundToInt

/** Вход аналитики: сырые подходы, тренировки, карта мышц и «сейчас». */
data class AnalyticsInput(
    val sets: List<AnalyticsSetRow>,
    val workouts: List<WorkoutEntity>,
    val muscleMap: Map<Long, List<MuscleLoad>>,
    val nowMillis: Long,
    val zone: ZoneId,
)

/**
 * Считает всё, что показывает вкладка «Анализы». Чистая функция входа: ни Room, ни Android,
 * ни системного времени — поэтому каждая метрика проверяется обычным юнит-тестом.
 *
 * Общие соглашения, действующие во всех метриках:
 *
 * * **Рабочий подход** — выполненный подход силового упражнения или упражнения на время,
 *   не похожий на разминочный. Кардио в подходах не считается: «подход» там не единица работы
 *   (для кардио есть минуты и МЕТ). Разминка отсекается эвристикой: подход легче 60% от самого
 *   тяжёлого подхода этого упражнения в этой же тренировке — разминочный. RIR приложение
 *   не записывает, а без него это лучший доступный признак.
 * * **Эффективный подход мышцы** — рабочий подход, взвешенный по общей шкале вовлечения
 *   ([setWeightFor]): 100% даёт 1.0, 60% — 0.6, а стабилизация ниже 25% отсекается.
 * * **Тоннаж** — Σ вес × повторы по силовым подходам (как в итогах тренировки).
 * * Недельные величины — **средние за неделю окна**, иначе окна разной длины несравнимы.
 */
class AnalyticsEngine @Inject constructor() {

    fun analyze(input: AnalyticsInput, period: AnalysisPeriod): AnalyticsReport {
        val today = Instant.ofEpochMilli(input.nowMillis).atZone(input.zone).toLocalDate()
        val periodStartMillis = period.weeks?.let { weeks ->
            today.minusWeeks(weeks.toLong() - 1)
                .with(DayOfWeek.MONDAY)
                .atStartOfDay(input.zone)
                .toInstant()
                .toEpochMilli()
        } ?: Long.MIN_VALUE

        val sets = input.sets.filter { it.completedAt >= periodStartMillis }
        val workouts = input.workouts.filter { it.finishedAt != null && it.startedAt >= periodStartMillis }
        if (sets.isEmpty() && workouts.isEmpty()) return AnalyticsReport.empty(period)

        val hardSets = hardSets(sets)
        val weeks = periodWeeks(period, firstActivityMillis(input), today, input.zone)

        val muscleLoads = muscleLoads(hardSets, input.muscleMap, weeks, today, input.zone)
        val effectiveByMuscle = muscleLoads.associate { it.muscle to it.totalSets }

        return AnalyticsReport(
            hasData = hardSets.isNotEmpty() || workouts.isNotEmpty(),
            period = period,
            periodWeeks = weeks,
            sessions = workouts.size,
            sessionsPerWeek = workouts.size / weeks,
            totalTonnageKg = sets.sumOf { it.tonnage },
            totalHardSets = hardSets.size.toDouble(),
            avgSessionMinutes = averageSessionMinutes(workouts),
            cardioMinutes = sets.filter { it.exerciseType == ExerciseType.CARDIO }
                .sumOf { it.durationSec ?: 0 } / 60,
            aerobicMetMinutesPerWeek = metMinutes(sets) / weeks,
            weeklyPoints = weeklyPoints(sets, hardSets, workouts, period, today, input.zone),
            muscleLoads = muscleLoads,
            exercises = exerciseProgress(sets),
            // Окно намеренно игнорируется: острая и хроническая нагрузка определены
            // на фиксированных 7 и 28 днях, иначе показатель не сравним с литературой.
            workload = workloadRatio(hardSets(input.sets), input.nowMillis),
            balances = balances(effectiveByMuscle),
            records = records(input.sets),
            streakWeeks = streakWeeks(input.workouts, today, input.zone),
            daysSinceLast = input.workouts.maxOfOrNull { it.startedAt }?.let { last ->
                ChronoUnit.DAYS.between(
                    Instant.ofEpochMilli(last).atZone(input.zone).toLocalDate(),
                    today,
                ).toInt()
            },
        )
    }

    // region shared helpers

    /**
     * Рабочие подходы: силовые и на время, без разминочных. Порог разминки — 60% от лучшего
     * веса этого упражнения в этой тренировке; упражнения без веса (свой вес, планка) проходят
     * целиком, там сравнивать нечего.
     */
    private fun hardSets(sets: List<AnalyticsSetRow>): List<AnalyticsSetRow> {
        val topWeights = sets
            .filter { it.exerciseType == ExerciseType.STRENGTH }
            .groupBy { it.workoutId to it.exerciseId }
            .mapValues { (_, group) -> group.maxOfOrNull { it.weightKg ?: 0.0 } ?: 0.0 }
        return sets.filter { set ->
            if (set.exerciseType == ExerciseType.CARDIO) return@filter false
            if (set.reps != null && set.reps !in 1..30) return@filter false
            val top = topWeights[set.workoutId to set.exerciseId] ?: 0.0
            top <= 0.0 || (set.weightKg ?: 0.0) >= 0.6 * top
        }
    }

    private val AnalyticsSetRow.tonnage: Double
        get() = if (exerciseType == ExerciseType.STRENGTH && weightKg != null && reps != null) {
            weightKg * reps
        } else {
            0.0
        }

    private fun AnalyticsSetRow.date(zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(completedAt).atZone(zone).toLocalDate()

    /**
     * Длина окна в неделях — знаменатель всех «в неделю». Для конечного окна это его длина,
     * но не больше **всей** прожитой истории: у новичка с одной тренировкой на прошлой неделе
     * делить на 12 недель нельзя, получится «0.1 подхода в неделю».
     *
     * Важно, что история считается по всем данным, а не по попавшим в окно: иначе двухнедельный
     * простой в начале окна сократил бы знаменатель и выдал бы простой за рост объёма.
     */
    private fun periodWeeks(
        period: AnalysisPeriod,
        firstActivityMillis: Long?,
        today: LocalDate,
        zone: ZoneId,
    ): Double {
        if (firstActivityMillis == null) return 1.0
        val firstDate = Instant.ofEpochMilli(firstActivityMillis).atZone(zone).toLocalDate()
        val historyWeeks = (ChronoUnit.DAYS.between(firstDate, today) + 1) / 7.0
        val windowWeeks = period.weeks?.toDouble() ?: historyWeeks
        return maxOf(1.0, minOf(windowWeeks, historyWeeks))
    }

    /** Момент самой первой активности во всей истории (не только в окне). */
    private fun firstActivityMillis(input: AnalyticsInput): Long? = minOf(
        input.sets.minOfOrNull { it.completedAt } ?: Long.MAX_VALUE,
        input.workouts.minOfOrNull { it.startedAt } ?: Long.MAX_VALUE,
    ).takeIf { it != Long.MAX_VALUE }

    // endregion

    // region muscles

    private fun muscleLoads(
        hardSets: List<AnalyticsSetRow>,
        muscleMap: Map<Long, List<MuscleLoad>>,
        weeks: Double,
        today: LocalDate,
        zone: ZoneId,
    ): List<MuscleLoadSummary> {
        val totals = mutableMapOf<Muscle, Double>()
        val tonnage = mutableMapOf<Muscle, Double>()
        val perDay = mutableMapOf<Muscle, MutableMap<LocalDate, Double>>()
        val perExercise = mutableMapOf<Muscle, MutableMap<String, Double>>()

        for (set in hardSets) {
            val loads = muscleMap[set.exerciseId].orEmpty()
            val date = set.date(zone)
            for (load in loads) {
                val share = setWeightFor(load.contribution)
                if (share <= 0.0) continue
                totals[load.muscle] = (totals[load.muscle] ?: 0.0) + share
                // Тоннаж мышцы взвешивается непрерывной долей, а не ступенькой: он описывает
                // «сколько килограммов прошло через мышцу», и стабилизаторы там тоже участвуют.
                tonnage[load.muscle] = (tonnage[load.muscle] ?: 0.0) +
                    set.tonnage * (load.contribution / 100.0)
                val days = perDay.getOrPut(load.muscle) { mutableMapOf() }
                days[date] = (days[date] ?: 0.0) + share
                val exercises = perExercise.getOrPut(load.muscle) { mutableMapOf() }
                exercises[set.exerciseName] = (exercises[set.exerciseName] ?: 0.0) + share
            }
        }

        return Muscle.entries.map { muscle ->
            val total = totals[muscle] ?: 0.0
            val weekly = total / weeks
            // Днём тренировки мышцы считается день, где она набрала хотя бы один полный
            // эффективный подход: иначе «трицепс тренировался» после единственного жима.
            val trainedDays = perDay[muscle].orEmpty().filterValues { it >= 1.0 }.keys
            MuscleLoadSummary(
                muscle = muscle,
                weeklySets = weekly,
                totalSets = total,
                tonnageKg = tonnage[muscle] ?: 0.0,
                zone = muscle.zoneFor(weekly),
                sessionsPerWeek = trainedDays.size / weeks,
                daysSinceLast = trainedDays.maxOrNull()
                    ?.let { ChronoUnit.DAYS.between(it, today).toInt() },
                topExercises = perExercise[muscle].orEmpty()
                    .entries
                    .sortedByDescending { it.value }
                    .take(3)
                    .map { it.key },
            )
        }
    }

    // endregion

    // region weekly

    private fun weeklyPoints(
        sets: List<AnalyticsSetRow>,
        hardSets: List<AnalyticsSetRow>,
        workouts: List<WorkoutEntity>,
        period: AnalysisPeriod,
        today: LocalDate,
        zone: ZoneId,
    ): List<WeeklyPoint> {
        val currentWeek = today.with(DayOfWeek.MONDAY)
        val firstMillis = minOf(
            workouts.minOfOrNull { it.startedAt } ?: Long.MAX_VALUE,
            sets.minOfOrNull { it.completedAt } ?: Long.MAX_VALUE,
        )
        if (firstMillis == Long.MAX_VALUE) return emptyList()
        val firstWeek = Instant.ofEpochMilli(firstMillis).atZone(zone).toLocalDate().with(DayOfWeek.MONDAY)
        val windowStart = period.weeks
            ?.let { currentWeek.minusWeeks(it.toLong() - 1) }
            ?.coerceAtLeast(firstWeek)
            ?: firstWeek

        val setsByWeek = hardSets.groupBy { it.date(zone).with(DayOfWeek.MONDAY) }
        val tonnageByWeek = sets.groupBy { it.date(zone).with(DayOfWeek.MONDAY) }
        val sessionsByWeek = workouts.groupBy {
            Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate().with(DayOfWeek.MONDAY)
        }

        // Пустые недели рисуем нулями, а не пропускаем: провал в тренировках — это тоже данные.
        val points = mutableListOf<WeeklyPoint>()
        var week = windowStart
        while (!week.isAfter(currentWeek)) {
            points += WeeklyPoint(
                weekStart = week,
                label = "%02d.%02d".format(week.dayOfMonth, week.monthValue),
                hardSets = (setsByWeek[week]?.size ?: 0).toDouble(),
                tonnageKg = tonnageByWeek[week].orEmpty().sumOf { it.tonnage },
                sessions = sessionsByWeek[week]?.size ?: 0,
                partial = week == currentWeek,
            )
            week = week.plusWeeks(1)
        }
        return points
    }

    /** МЕТ-минуты аэробной работы за окно: только кардио — полоса ВОЗ определена именно для него. */
    private fun metMinutes(sets: List<AnalyticsSetRow>): Double =
        sets.filter { it.exerciseType == ExerciseType.CARDIO }
            .sumOf { set ->
                val minutes = (set.durationSec ?: 0) / 60.0
                if (minutes <= 0.0) 0.0 else minutes * CardioMet.forSet(set.exerciseName, set.speedKmh, set.inclinePct)
            }

    private fun averageSessionMinutes(workouts: List<WorkoutEntity>): Int {
        val durations = workouts.mapNotNull { workout ->
            workout.finishedAt?.let { (it - workout.startedAt).coerceAtLeast(0L) }
        }
        if (durations.isEmpty()) return 0
        return (durations.average() / 60_000.0).roundToInt()
    }

    private fun streakWeeks(workouts: List<WorkoutEntity>, today: LocalDate, zone: ZoneId): Int {
        if (workouts.isEmpty()) return 0
        val trainedWeeks = workouts
            .filter { it.finishedAt != null }
            .mapTo(mutableSetOf()) {
                Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate().with(DayOfWeek.MONDAY)
            }
        // Текущая неделя ещё может «доиграться», поэтому серия, прерванная только ею, не рвётся:
        // отсчёт начинаем с последней недели, где тренировка уже была.
        var week = today.with(DayOfWeek.MONDAY)
        if (week !in trainedWeeks) week = week.minusWeeks(1)
        var streak = 0
        while (week in trainedWeeks) {
            streak++
            week = week.minusWeeks(1)
        }
        return streak
    }

    // endregion

    // region exercises

    private fun exerciseProgress(sets: List<AnalyticsSetRow>): List<ExerciseProgress> =
        sets.filter { it.exerciseType == ExerciseType.STRENGTH }
            .groupBy { it.exerciseId }
            .map { (exerciseId, exerciseSets) -> progressFor(exerciseId, exerciseSets) }
            .filter { it.points.isNotEmpty() }
            .sortedWith(compareByDescending<ExerciseProgress> { it.points.size }.thenBy { it.name })

    private fun progressFor(exerciseId: Long, sets: List<AnalyticsSetRow>): ExerciseProgress {
        val points = sets.groupBy { it.workoutId }
            .mapNotNull { (workoutId, sessionSets) ->
                val bestE1rm = sessionSets.mapNotNull { OneRepMax.epley(it.weightKg, it.reps) }.maxOrNull()
                    ?: return@mapNotNull null
                val heaviest = sessionSets
                    .filter { it.weightKg != null && it.reps != null }
                    .maxByOrNull { it.weightKg!! }
                ExerciseSessionPoint(
                    workoutId = workoutId,
                    dateMillis = sessionSets.minOf { it.completedAt },
                    bestE1rm = bestE1rm,
                    bestWeightKg = heaviest?.weightKg ?: 0.0,
                    bestWeightReps = heaviest?.reps ?: 0,
                    tonnageKg = sessionSets.sumOf { it.tonnage },
                    sets = sessionSets.size,
                )
            }
            .sortedBy { it.dateMillis }

        val slopePerDay = e1rmSlopePerDay(points)
        val kgPerMonth = slopePerDay?.times(30.0)
        val meanE1rm = points.map { it.bestE1rm }.average().takeIf { points.isNotEmpty() }
        return ExerciseProgress(
            exerciseId = exerciseId,
            name = sets.first().exerciseName,
            points = points,
            repMaxes = repMaxes(sets),
            bestE1rm = points.maxOfOrNull { it.bestE1rm },
            trendPercentPerMonth = if (kgPerMonth != null && meanE1rm != null && meanE1rm > 0.0) {
                kgPerMonth / meanE1rm * 100.0
            } else {
                null
            },
            trendKgPerMonth = kgPerMonth,
            verdict = verdict(points, kgPerMonth),
        )
    }

    /**
     * Вердикт по тренду. Порог заметности — `max(1.25 кг, 1% от текущего e1RM)` за месяц:
     * 1.25 кг это типичный минимальный шаг блинов, ниже него «изменение» неотличимо от
     * дискретности снаряда. Вердикт выносится только при ≥ 5 тренировках.
     */
    private fun verdict(points: List<ExerciseSessionPoint>, kgPerMonth: Double?): TrendVerdict {
        if (points.size < 5 || kgPerMonth == null) return TrendVerdict.NOT_ENOUGH_DATA
        val current = points.last().bestE1rm
        val threshold = maxOf(1.25, current * 0.01)
        return when {
            kgPerMonth > threshold -> TrendVerdict.GROWING
            kgPerMonth < -threshold -> TrendVerdict.REGRESSING
            else -> TrendVerdict.STALLED
        }
    }

    /**
     * Силовая кривая: для каждого числа повторений — самый тяжёлый вес, поднятый **хотя бы**
     * на столько раз. Условие «хотя бы» делает кривую монотонной: подход 100 кг × 8 закрывает
     * и «100 кг на 5», которое иначе выглядело бы как пробел в данных.
     */
    private fun repMaxes(sets: List<AnalyticsSetRow>): List<RepMaxPoint> {
        val usable = sets.filter { it.weightKg != null && it.reps != null && it.weightKg > 0.0 }
        if (usable.isEmpty()) return emptyList()
        return (1..OneRepMax.MAX_TRUSTED_REPS).mapNotNull { reps ->
            val best = usable.filter { it.reps!! >= reps }.maxByOrNull { it.weightKg!! }
                ?: return@mapNotNull null
            RepMaxPoint(reps = reps, weightKg = best.weightKg!!, dateMillis = best.completedAt)
        }
    }

    /**
     * Наклон e1RM в килограммах за день — обычный МНК по (день, e1RM). При < 3 точках или
     * нулевом разбросе по времени тренда нет: по двум тренировкам «рост 40%/мес» это шум.
     */
    private fun e1rmSlopePerDay(points: List<ExerciseSessionPoint>): Double? {
        if (points.size < 3) return null
        val days = points.map { it.dateMillis / 86_400_000.0 }
        val values = points.map { it.bestE1rm }
        val meanX = days.average()
        val meanY = values.average()
        var numerator = 0.0
        var denominator = 0.0
        for (i in points.indices) {
            val dx = days[i] - meanX
            numerator += dx * (values[i] - meanY)
            denominator += dx * dx
        }
        if (denominator <= 0.0) return null
        return numerator / denominator
    }

    private fun records(sets: List<AnalyticsSetRow>): List<ExerciseRecord> =
        sets.filter { it.exerciseType == ExerciseType.STRENGTH }
            .groupBy { it.exerciseId }
            .mapNotNull { (exerciseId, exerciseSets) ->
                val best = exerciseSets
                    .mapNotNull { set -> OneRepMax.epley(set.weightKg, set.reps)?.let { set to it } }
                    .maxByOrNull { it.second }
                    ?: return@mapNotNull null
                ExerciseRecord(
                    exerciseId = exerciseId,
                    name = best.first.exerciseName,
                    bestE1rm = best.second,
                    weightKg = best.first.weightKg ?: 0.0,
                    reps = best.first.reps ?: 0,
                    dateMillis = best.first.completedAt,
                )
            }
            .sortedByDescending { it.bestE1rm }

    // endregion

    // region load and balance

    private fun workloadRatio(hardSets: List<AnalyticsSetRow>, nowMillis: Long): WorkloadRatio {
        if (hardSets.isEmpty()) return WorkloadRatio(0.0, 0.0, null, hasEnoughData = false)
        val day = 86_400_000L
        val acute = hardSets.count { it.completedAt >= nowMillis - 7 * day }.toDouble()
        val chronicTotal = hardSets.count { it.completedAt >= nowMillis - 28 * day }.toDouble()
        val chronicWeekly = chronicTotal / 4.0
        val historyDays = (nowMillis - hardSets.minOf { it.completedAt }) / day
        val enough = historyDays >= 28 && chronicWeekly > 0.0
        return WorkloadRatio(
            acuteSets = acute,
            chronicWeeklySets = chronicWeekly,
            ratio = if (chronicWeekly > 0.0) acute / chronicWeekly else null,
            hasEnoughData = enough,
        )
    }

    private fun balances(effectiveByMuscle: Map<Muscle, Double>): List<BalanceRatio> {
        fun sum(vararg muscles: Muscle) = muscles.sumOf { effectiveByMuscle[it] ?: 0.0 }

        val push = sum(Muscle.CHEST, Muscle.FRONT_DELTS, Muscle.TRICEPS)
        val pull = sum(Muscle.LATS, Muscle.UPPER_BACK, Muscle.REAR_DELTS, Muscle.BICEPS, Muscle.TRAPS)
        val upper = sum(
            Muscle.CHEST, Muscle.FRONT_DELTS, Muscle.SIDE_DELTS, Muscle.REAR_DELTS, Muscle.TRAPS,
            Muscle.LATS, Muscle.UPPER_BACK, Muscle.BICEPS, Muscle.TRICEPS, Muscle.FOREARMS,
        )
        val lower = sum(
            Muscle.GLUTES, Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.ADDUCTORS, Muscle.CALVES,
        )
        val quads = sum(Muscle.QUADS)
        val hamstrings = sum(Muscle.HAMSTRINGS)
        val anterior = sum(
            Muscle.CHEST, Muscle.FRONT_DELTS, Muscle.BICEPS, Muscle.ABS, Muscle.QUADS,
        )
        val posterior = sum(
            Muscle.LATS, Muscle.UPPER_BACK, Muscle.LOWER_BACK, Muscle.REAR_DELTS, Muscle.TRAPS,
            Muscle.GLUTES, Muscle.HAMSTRINGS,
        )

        return listOf(
            // Жим к тяге ≈ 1:1 — общая рекомендация против передне-доминантного перекоса
            // (грудь и передняя дельта тянут плечо вперёд, спина возвращает баланс).
            BalanceRatio(BalanceId.PUSH_PULL, push, pull, ratio(push, pull), 0.8, 1.25),
            // Перёд к заду — та же логика на уровне всего тела.
            BalanceRatio(
                BalanceId.ANTERIOR_POSTERIOR, anterior, posterior, ratio(anterior, posterior), 0.8, 1.25,
            ),
            // У верха и низа «правильного» отношения нет — оно зависит от целей. Флажок
            // ставится только на крайности за пределами 1:2..2:1.
            BalanceRatio(BalanceId.UPPER_LOWER, upper, lower, ratio(upper, lower), 0.5, 2.0),
            // Задняя поверхность бедра — не меньше половины объёма квадрицепса: с этим
            // соотношением связывают устойчивость колена и профилактику травм задней группы.
            BalanceRatio(BalanceId.QUAD_HAMSTRING, hamstrings, quads, ratio(hamstrings, quads), 0.5, 1.5),
        )
    }

    private fun ratio(left: Double, right: Double): Double? =
        if (right > 0.0) left / right else null

    // endregion
}
