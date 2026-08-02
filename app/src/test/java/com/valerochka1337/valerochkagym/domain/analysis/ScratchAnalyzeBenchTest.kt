package com.valerochka1337.valerochkagym.domain.analysis

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.relation.AnalyticsSetRow
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ScratchAnalyzeBenchTest {

    private val engine = AnalyticsEngine()
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val today: LocalDate = LocalDate.of(2026, 6, 10)
    private val nowMillis = today.atTime(20, 0).atZone(zone).toInstant().toEpochMilli()

    private fun build(years: Int): AnalyticsInput {
        val sets = mutableListOf<AnalyticsSetRow>()
        val workouts = mutableListOf<WorkoutEntity>()
        val exercises = 40
        val days = years * 365
        var day = days
        var w = 0
        while (day > 0) {
            // 4 тренировки в неделю по 24 подхода (6 упражнений x 4)
            val id = "w-$w"
            val start = today.minusDays(day.toLong()).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
            workouts += WorkoutEntity(
                id = id,
                name = "T",
                startedAt = start,
                finishedAt = start + 70 * 60_000L,
                uploadStatus = UploadStatus.UPLOADED,
            )
            for (e in 0 until 6) {
                val exId = ((w * 6 + e) % exercises).toLong()
                for (s in 0 until 4) {
                    sets += AnalyticsSetRow(
                        workoutId = id,
                        exerciseId = exId,
                        exerciseName = "Упражнение $exId",
                        exerciseType = if (exId % 10 == 0L) ExerciseType.CARDIO else ExerciseType.STRENGTH,
                        weightKg = 40.0 + (s * 5) + (w % 20),
                        reps = 5 + s,
                        durationSec = if (exId % 10 == 0L) 1800 else null,
                        speedKmh = if (exId % 10 == 0L) 9.0 else null,
                        inclinePct = null,
                        completedAt = start + (e * 4 + s) * 120_000L,
                    )
                }
            }
            w++
            day -= 2 // ~3.5 тренировки в неделю
        }
        sets.sortBy { it.completedAt }
        val muscleMap = (0 until exercises).associate { id ->
            id.toLong() to listOf(
                MuscleLoad(Muscle.entries[id % Muscle.entries.size], 100),
                MuscleLoad(Muscle.entries[(id + 3) % Muscle.entries.size], 65),
                MuscleLoad(Muscle.entries[(id + 7) % Muscle.entries.size], 40),
                MuscleLoad(Muscle.entries[(id + 11) % Muscle.entries.size], 15),
            )
        }
        return AnalyticsInput(sets, workouts, muscleMap, nowMillis, zone)
    }

    @Test
    fun bench() {
        for (years in listOf(1, 2, 3, 5)) {
            val input = build(years)
            // warmup
            repeat(20) {
                engine.analyze(input, AnalysisPeriod.YEAR)
                engine.analyze(input, AnalysisPeriod.ALL)
            }
            for (period in listOf(AnalysisPeriod.WEEKS_4, AnalysisPeriod.YEAR, AnalysisPeriod.ALL)) {
                val times = LongArray(15) {
                    val t0 = System.nanoTime()
                    engine.analyze(input, period)
                    System.nanoTime() - t0
                }
                times.sort()
                println(
                    "years=$years sets=${input.sets.size} workouts=${input.workouts.size} " +
                        "period=$period median=${times[7] / 1000}us max=${times[14] / 1000}us",
                )
            }
        }
    }
}
