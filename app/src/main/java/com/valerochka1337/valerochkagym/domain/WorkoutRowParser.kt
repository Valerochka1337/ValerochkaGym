package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Разобранная тренировка из листа `Workouts`. Времена — epoch millis в системной таймзоне. */
data class ParsedWorkout(
    val id: String,
    val name: String,
    val startedAt: Long,
    val finishedAt: Long,
    val exercises: List<ParsedExercise>,
)

data class ParsedExercise(
    val name: String,
    val muscleGroup: MuscleGroup,
    val type: ExerciseType,
    val position: Int,
    val sectionId: String? = null,
    val exerciseSyncId: String? = null,
    val sets: List<ParsedSet>,
)

data class ParsedSet(
    val setIndex: Int,
    val weightKg: Double?,
    val reps: Int?,
    val durationSec: Int?,
    val speedKmh: Double?,
    val inclinePct: Double?,
    val completedAt: Long,
)

/**
 * Итог разбора листа: дерево тренировок плюс счётчик [skippedRows] — строк с `workout_id`,
 * которые не удалось разобрать (битое время). Счётчик всплывает до пользователя: молчаливый
 * пропуск превращал бы кривую таблицу в «нечего импортировать».
 */
data class ParsedRows(
    val workouts: List<ParsedWorkout>,
    val skippedRows: Int,
    val fatalError: String? = null,
)

/**
 * Обратный к [WorkoutRowMapper]: собирает плоские строки листа `Workouts` (в порядке
 * [WorkoutRowMapper.HEADER_ROW]) в дерево тренировок.
 *
 * Строка-шапка и пустые строки (без `workout_id`) игнорируются молча; строки с id, но с
 * нераспознанным временем (`date`+`start_time`) считаются в [ParsedRows.skippedRows].
 * Строки группируются по `workout_id` (порядок появления сохраняется), внутри — по имени
 * упражнения в порядке первого появления (это даёт `position`). `startedAt`/`finishedAt`
 * тренировки — минимум/максимум `completedAt` её подходов. Числа парсятся мягко
 * (запятая-десятичный разделитель допускается); пустая ячейка → `null`.
 */
object WorkoutRowParser {

    private val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private const val COL_WORKOUT_ID = 0
    private const val COL_DATE = 1
    private const val COL_START_TIME = 2
    private const val COL_WORKOUT_NAME = 3
    private const val COL_EXERCISE = 4
    private const val COL_MUSCLE_GROUP = 5
    private const val COL_TYPE = 6
    private const val COL_SET_INDEX = 7
    private const val COL_WEIGHT = 8
    private const val COL_REPS = 9
    private const val COL_DURATION = 10
    private const val COL_SPEED = 11
    private const val COL_INCLINE = 12
    private const val COL_SECTION_ID = 14
    private const val COL_SECTION_POSITION = 15
    private const val COL_EXERCISE_ID = 16

    fun parse(rows: List<List<String>>): ParsedRows {
        val zone = ZoneId.systemDefault()
        var skippedRows = 0
        val header = rows.firstOrNull().orEmpty()
        val currentFormat = header == WorkoutRowMapper.HEADER_ROW
        if (!currentFormat && header.size >= WorkoutRowMapper.HEADER_ROW.size) {
            return ParsedRows(emptyList(), 0, "Заголовок Workouts A:S изменён вручную")
        }
        var malformedCurrent = false

        // Сырые подходы, сгруппированные по workout_id с сохранением порядка появления.
        data class RawSet(
            val exercise: String,
            val muscleGroup: MuscleGroup,
            val type: ExerciseType,
            val setIndex: Int,
            val weightKg: Double?,
            val reps: Int?,
            val durationSec: Int?,
            val speedKmh: Double?,
            val inclinePct: Double?,
            val completedAt: Long,
            val sectionId: String?,
            val sectionPosition: Int?,
            val exerciseSyncId: String?,
        )

        val grouped = LinkedHashMap<String, Pair<String, MutableList<RawSet>>>()

        for (row in rows) {
            val id = row.cell(COL_WORKOUT_ID)
            if (id.isEmpty() || id == "workout_id") continue // пропуск шапки/пустых
            val millis = parseMillis(row.cell(COL_DATE), row.cell(COL_START_TIME), zone)
            if (millis == null) {
                skippedRows++
                continue
            }
            val sectionId = canonicalSheetUuidOrNull(row.cell(COL_SECTION_ID))
            val exerciseSyncId = canonicalSheetUuidOrNull(row.cell(COL_EXERCISE_ID))
            val sectionPosition = row.cell(COL_SECTION_POSITION).toIntOrNull()
            if (currentFormat && (sectionId == null || exerciseSyncId == null || sectionPosition == null || sectionPosition < 0)) {
                malformedCurrent = true
                continue
            }

            val (_, sets) = grouped.getOrPut(id) { row.cell(COL_WORKOUT_NAME) to mutableListOf() }
            sets.add(
                RawSet(
                    exercise = row.cell(COL_EXERCISE),
                    muscleGroup = muscleGroupFrom(row.cell(COL_MUSCLE_GROUP)),
                    type = exerciseTypeFrom(row.cell(COL_TYPE)),
                    setIndex = (row.cell(COL_SET_INDEX).toIntOrNull() ?: (sets.size + 1)) - 1,
                    weightKg = row.cell(COL_WEIGHT).toDoubleLoose(),
                    reps = row.cell(COL_REPS).toIntOrNull(),
                    durationSec = row.cell(COL_DURATION).toIntOrNull(),
                    speedKmh = row.cell(COL_SPEED).toDoubleLoose(),
                    inclinePct = row.cell(COL_INCLINE).toDoubleLoose(),
                    completedAt = millis,
                    sectionId = sectionId,
                    sectionPosition = sectionPosition,
                    exerciseSyncId = exerciseSyncId,
                ),
            )
        }

        val workouts = grouped.map { (id, value) ->
            val (name, raws) = value
            if (currentFormat && raws.groupBy { it.sectionId }.values.any { section ->
                    section.map { raw ->
                        listOf(raw.exercise, raw.muscleGroup, raw.type, raw.exerciseSyncId, raw.sectionPosition)
                    }.distinct().size != 1
                }
            ) {
                malformedCurrent = true
            }
            val times = raws.map { it.completedAt }
            val exercises = LinkedHashMap<String, MutableList<RawSet>>()
            raws.forEach { raw ->
                exercises.getOrPut(raw.sectionId ?: raw.exercise) { mutableListOf() }.add(raw)
            }

            ParsedWorkout(
                id = id,
                name = name,
                startedAt = times.minOrNull()!!, // группа непустая: у неё ≥1 подход
                finishedAt = times.maxOrNull()!!,
                exercises = exercises.entries.mapIndexed { position, (_, exerciseSets) ->
                    val first = exerciseSets.first()
                    ParsedExercise(
                        name = first.exercise,
                        muscleGroup = first.muscleGroup,
                        type = first.type,
                        position = first.sectionPosition ?: position,
                        sectionId = first.sectionId,
                        exerciseSyncId = first.exerciseSyncId,
                        sets = exerciseSets.map { s ->
                            ParsedSet(
                                setIndex = s.setIndex,
                                weightKg = s.weightKg,
                                reps = s.reps,
                                durationSec = s.durationSec,
                                speedKmh = s.speedKmh,
                                inclinePct = s.inclinePct,
                                completedAt = s.completedAt,
                            )
                        },
                    )
                },
            )
        }
        return ParsedRows(
            workouts = workouts,
            skippedRows = skippedRows,
            fatalError = if (malformedCurrent) "Строки Workouts A:S содержат некорректный снимок секции" else null,
        )
    }

    private fun List<String>.cell(index: Int): String = getOrNull(index)?.trim().orEmpty()

    /** Мягкий разбор Double: пустая строка → null; запятая-разделитель приводится к точке. */
    private fun String.toDoubleLoose(): Double? =
        takeIf { it.isNotEmpty() }?.replace(',', '.')?.toDoubleOrNull()

    private fun parseMillis(date: String, time: String, zone: ZoneId): Long? {
        if (date.isEmpty() || time.isEmpty()) return null
        return try {
            LocalDateTime.parse("$date $time", DATE_TIME_FORMATTER)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        } catch (e: java.time.format.DateTimeParseException) {
            null
        }
    }
}
