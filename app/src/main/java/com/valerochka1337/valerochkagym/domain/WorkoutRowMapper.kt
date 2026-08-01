package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Разворачивает тренировку в плоские строки для Google Sheets: одна строка на каждый
 * выполненный подход. Порядок колонок строго соответствует [HEADER_ROW] — эти два списка
 * связаны контрактом и должны меняться синхронно.
 *
 * Колонки:
 *  - `workout_id`   — [com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity.id] (UUID).
 *  - `date`         — дата начала тренировки `yyyy-MM-dd` в локальной таймзоне.
 *  - `start_time`   — время начала `HH:mm` в локальной таймзоне.
 *  - `workout_name` — название тренировки.
 *  - `exercise`     — название упражнения.
 *  - `muscle_group` — русское отображаемое имя группы мышц.
 *  - `type`         — русское отображаемое имя типа упражнения.
 *  - `set_index`    — человеческая нумерация подхода (setIndex + 1).
 *  - `weight_kg`    — вес (Double) или null.
 *  - `reps`         — повторы (Int) или null.
 *  - `duration_sec` — длительность в секундах (Int) или null.
 *  - `speed_kmh`    — скорость (Double) или null.
 *  - `incline_pct`  — наклон в процентах (Double) или null.
 *  - `volume`       — вес × повторы (Double) для силовых подходов с обоими значениями, иначе null.
 *
 * Числа отдаются как числа (Double/Int), не строки. `null` означает пустую ячейку — JSON-слой
 * сериализации превращает его в "". Форматирование даты/времени локаль-независимо.
 */
object WorkoutRowMapper {

    private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    val HEADER_ROW: List<String> = listOf(
        "workout_id",
        "date",
        "start_time",
        "workout_name",
        "exercise",
        "muscle_group",
        "type",
        "set_index",
        "weight_kg",
        "reps",
        "duration_sec",
        "speed_kmh",
        "incline_pct",
        "volume",
    )

    /** Строки Sheets по выполненным подходам, упорядоченные по позиции упражнения и индексу подхода. */
    fun rows(workout: WorkoutFull): List<List<Any?>> {
        val startedAt = Instant.ofEpochMilli(workout.workout.startedAt).atZone(ZoneId.systemDefault())
        val date = DATE_FORMATTER.format(startedAt)
        val startTime = TIME_FORMATTER.format(startedAt)

        return workout.exercises
            .sortedBy { it.workoutExercise.position }
            .flatMap { exercise ->
                exercise.sets
                    .filter { it.isCompleted }
                    .sortedBy { it.setIndex }
                    .map { set ->
                        val volume = if (
                            exercise.exercise.type == ExerciseType.STRENGTH &&
                            set.weightKg != null &&
                            set.reps != null
                        ) {
                            set.weightKg * set.reps
                        } else {
                            null
                        }
                        listOf(
                            workout.workout.id,
                            date,
                            startTime,
                            workout.workout.name,
                            exercise.exercise.name,
                            // Экспорт намеренно использует русские UI-названия;
                            // изменение displayName() меняет семантику исторических выгрузок.
                            exercise.exercise.muscleGroup.displayName(),
                            exercise.exercise.type.displayName(),
                            set.setIndex + 1,
                            set.weightKg,
                            set.reps,
                            set.durationSec,
                            set.speedKmh,
                            set.inclinePct,
                            volume,
                        )
                    }
            }
    }
}
