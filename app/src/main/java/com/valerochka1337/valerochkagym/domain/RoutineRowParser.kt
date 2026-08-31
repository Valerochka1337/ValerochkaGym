package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

data class ParsedRoutine(
    val syncId: String,
    val updatedAt: Long,
    val name: String,
    val note: String,
    val isDeleted: Boolean,
    val exercises: List<ParsedRoutineExercise>,
)

data class ParsedRoutineExercise(
    val syncId: String? = null,
    val name: String,
    val muscleGroup: MuscleGroup,
    val type: ExerciseType,
    val position: Int,
    val restSeconds: Int?,
    val plannedSets: List<PlannedSet>,
)

/** Результат разбора snapshots из листа `Routines`. */
data class ParsedRoutineRows(
    val routines: List<ParsedRoutine>,
    val skippedRows: Int,
)

/**
 * Собирает append-only снимки программ. Для каждого `routine_id` выбирается максимальный
 * `updated_at`; равная версия остаётся последней встретившейся в листе. Это даёт повторной
 * выгрузке и догоняющему воркеру идемпотентное поведение.
 */
object RoutineRowParser {

    private data class Snapshot(
        val syncId: String,
        val updatedAt: Long,
        var name: String = "",
        var note: String = "",
        var isDeleted: Boolean = false,
        var hasEmptyExerciseMarker: Boolean = false,
        var hasStableExerciseRows: Boolean = false,
        val exercises: MutableList<ParsedRoutineExercise> = mutableListOf(),
    )

    fun parse(rows: List<List<String>>): ParsedRoutineRows {
        var skippedRows = 0
        val snapshots = LinkedHashMap<String, LinkedHashMap<Long, Snapshot>>()

        rows.forEach { row ->
            val syncId = row.cell(ROUTINE_ID)
            if (syncId.isEmpty() || syncId == "routine_id") return@forEach
            val updatedAt = row.cell(UPDATED_AT).toLongLoose()
            if (updatedAt == null) {
                skippedRows++
                return@forEach
            }
            val snapshot = snapshots
                .getOrPut(syncId) { LinkedHashMap() }
                .getOrPut(updatedAt) { Snapshot(syncId, updatedAt) }
            if (row.cell(IS_DELETED).isTrue()) {
                snapshot.isDeleted = true
                return@forEach
            }

            val name = row.cell(ROUTINE_NAME)
            if (name.isEmpty()) {
                skippedRows++
                return@forEach
            }
            snapshot.name = name
            snapshot.note = row.cell(ROUTINE_NOTE)
            val exerciseName = row.cell(EXERCISE_NAME)
            if (exerciseName.isEmpty()) {
                snapshot.hasEmptyExerciseMarker = true
                return@forEach
            }
            val plannedSets = decodePlannedSets(row.cell(PLANNED_SETS_JSON))
            if (plannedSets == null) {
                skippedRows++
                return@forEach
            }
            val rawExerciseId = row.cell(EXERCISE_ID)
            val exerciseId = if (rawExerciseId.isEmpty()) {
                null
            } else {
                canonicalSheetUuidOrNull(rawExerciseId) ?: run {
                    skippedRows++
                    return@forEach
                }
            }
            if (exerciseId != null && !snapshot.hasStableExerciseRows) {
                // При первом v2-ряду той же версии отбрасываем старые name-only строки.
                snapshot.exercises.clear()
                snapshot.hasStableExerciseRows = true
            } else if (exerciseId == null && snapshot.hasStableExerciseRows) {
                return@forEach
            }
            snapshot.exercises += ParsedRoutineExercise(
                syncId = exerciseId,
                name = exerciseName,
                muscleGroup = row.cell(MUSCLE_GROUP).toMuscleGroup(),
                type = row.cell(TYPE).toExerciseType(),
                position = row.cell(EXERCISE_POSITION).toIntLoose() ?: snapshot.exercises.size,
                restSeconds = row.cell(REST_SECONDS).toIntLoose(),
                plannedSets = plannedSets,
            )
        }

        val routines = snapshots.values.mapNotNull { versions ->
            // Версии — ключи LinkedHashMap, поэтому один snapshot на updatedAt уже собрал все
            // строки его программы. Берём последнюю (наибольшую) версию.
            val snapshot = versions.values.maxByOrNull { it.updatedAt } ?: return@mapNotNull null
            when {
                snapshot.isDeleted -> ParsedRoutine(
                    syncId = snapshot.syncId,
                    updatedAt = snapshot.updatedAt,
                    name = "",
                    note = "",
                    isDeleted = true,
                    exercises = emptyList(),
                )
                snapshot.name.isEmpty() ||
                    (snapshot.exercises.isEmpty() && !snapshot.hasEmptyExerciseMarker) -> null
                else -> ParsedRoutine(
                    syncId = snapshot.syncId,
                    updatedAt = snapshot.updatedAt,
                    name = snapshot.name,
                    note = snapshot.note,
                    isDeleted = false,
                    exercises = snapshot.exercises
                        .distinctBy { it.position to (it.syncId ?: it.name.lowercase()) }
                        .sortedBy { it.position },
                )
            }
        }
        return ParsedRoutineRows(routines, skippedRows)
    }

    private fun List<String>.cell(index: Int): String = getOrNull(index)?.trim().orEmpty()

    private fun String.toLongLoose(): Long? =
        replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite)?.let { value ->
            value.toLong().takeIf { value == it.toDouble() }
        }

    private fun String.toIntLoose(): Int? =
        toLongLoose()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    private fun String.isTrue(): Boolean = trim().equals("true", ignoreCase = true) || trim() == "1"

    private fun String.toMuscleGroup(): MuscleGroup =
        MuscleGroup.entries.firstOrNull { it.name.equals(trim(), ignoreCase = true) }
            ?: muscleGroupFrom(this)

    private fun String.toExerciseType(): ExerciseType =
        ExerciseType.entries.firstOrNull { it.name.equals(trim(), ignoreCase = true) }
            ?: exerciseTypeFrom(this)

    private fun decodePlannedSets(value: String): List<PlannedSet>? =
        if (value.isBlank()) {
            emptyList()
        } else {
            runCatching { JSON.decodeFromString<List<PlannedSet>>(value) }.getOrNull()
        }

    private const val ROUTINE_ID = 0
    private const val UPDATED_AT = 1
    private const val IS_DELETED = 2
    private const val ROUTINE_NAME = 3
    private const val ROUTINE_NOTE = 4
    private const val EXERCISE_POSITION = 5
    private const val EXERCISE_NAME = 6
    private const val MUSCLE_GROUP = 7
    private const val TYPE = 8
    private const val REST_SECONDS = 9
    private const val PLANNED_SETS_JSON = 10
    private const val EXERCISE_ID = 11

    private val JSON = Json { ignoreUnknownKeys = true }
}
