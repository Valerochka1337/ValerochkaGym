package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup

/** Контракт append-only листа `Exercises` (A:I). */
object ExerciseSheetRowMapper {
    const val SHEET_NAME = "Exercises"
    const val RANGE = "Exercises!A:I"

    val HEADER_ROW: List<String> = listOf(
        "exercise_id",
        "updated_at",
        "is_deleted",
        "exercise_name",
        "muscle_group",
        "type",
        "is_custom",
        "muscle",
        "contribution",
    )

    fun rows(record: ExerciseSheetRecord): List<List<Any?>> = when (record) {
        is ExerciseSheetRecord.Snapshot -> snapshotRows(record)
        is ExerciseSheetRecord.Tombstone -> listOf(deletion(record.syncId, record.updatedAt))
    }

    fun deletion(syncId: String, updatedAt: Long): List<Any?> {
        val canonicalId = requireCanonicalSheetUuid(syncId, "exercise_id")
        requireSheetVersion(updatedAt)
        return listOf(canonicalId, updatedAt, "true", "", "", "", "", "", "")
    }

    private fun snapshotRows(snapshot: ExerciseSheetRecord.Snapshot): List<List<Any?>> {
        val canonicalId = requireCanonicalSheetUuid(snapshot.syncId, "exercise_id")
        requireSheetVersion(snapshot.updatedAt)
        require(snapshot.name.isNotBlank()) { "exercise_name не должен быть пустым" }
        snapshot.muscleLoads.forEach { (_, contribution) ->
            require(contribution in MIN_CONTRIBUTION..MAX_CONTRIBUTION) {
                "contribution должен быть в диапазоне 0..100"
            }
        }
        val base = listOf<Any?>(
            canonicalId,
            snapshot.updatedAt,
            "false",
            snapshot.name,
            snapshot.muscleGroup.name,
            snapshot.type.name,
            snapshot.isCustom.toString(),
        )
        if (snapshot.muscleLoads.isEmpty()) return listOf(base + listOf("", ""))
        return Muscle.entries.mapNotNull { muscle ->
            snapshot.muscleLoads[muscle]?.let { contribution ->
                base + listOf(muscle.name, contribution)
            }
        }
    }

    private const val MIN_CONTRIBUTION = 0
    private const val MAX_CONTRIBUTION = 100
}

/**
 * Строгий разбор `Exercises`: повреждённая последняя версия не заменяется более старой молча.
 */
object ExerciseSheetRowParser {
    private data class Metadata(
        val name: String,
        val muscleGroup: MuscleGroup,
        val type: ExerciseType,
        val isCustom: Boolean,
    )

    private data class SnapshotBuilder(
        val syncId: String,
        val updatedAt: Long,
        var isDeleted: Boolean? = null,
        var metadata: Metadata? = null,
        var hasEmptyMarker: Boolean = false,
        val muscleLoads: MutableMap<Muscle, Int> = linkedMapOf(),
        var invalid: Boolean = false,
    ) {
        fun accept(row: List<String>): Boolean {
            val deleted = row.sheetCell(IS_DELETED).toSheetBooleanOrNull() ?: return reject()
            isDeleted?.let { previous -> if (previous != deleted) return reject() }
            isDeleted = deleted

            if (deleted) {
                if (row.hasContentFrom(EXERCISE_NAME)) return reject()
                if (metadata != null || hasEmptyMarker || muscleLoads.isNotEmpty()) return reject()
                return true
            }

            val incoming = Metadata(
                name = row.sheetCell(EXERCISE_NAME).takeIf(String::isNotEmpty) ?: return reject(),
                muscleGroup = row.sheetCell(MUSCLE_GROUP).toEnumOrNull<MuscleGroup>() ?: return reject(),
                type = row.sheetCell(TYPE).toEnumOrNull<ExerciseType>() ?: return reject(),
                isCustom = row.sheetCell(IS_CUSTOM).toSheetBooleanOrNull() ?: return reject(),
            )
            metadata?.let { previous -> if (previous != incoming) return reject() }
            metadata = incoming

            val muscleCell = row.sheetCell(MUSCLE)
            val contributionCell = row.sheetCell(CONTRIBUTION)
            if (muscleCell.isEmpty() && contributionCell.isEmpty()) {
                if (muscleLoads.isNotEmpty()) return reject()
                hasEmptyMarker = true
                return true
            }
            if (muscleCell.isEmpty() || contributionCell.isEmpty() || hasEmptyMarker) return reject()
            val muscle = muscleCell.toEnumOrNull<Muscle>() ?: return reject()
            val contribution = contributionCell.toSheetIntOrNull()
                ?.takeIf { it in MIN_CONTRIBUTION..MAX_CONTRIBUTION }
                ?: return reject()
            val previous = muscleLoads[muscle]
            if (previous != null && previous != contribution) return reject()
            muscleLoads[muscle] = contribution
            return true
        }

        fun toRecord(): ExerciseSheetRecord? {
            if (invalid) return null
            return when (isDeleted) {
                true -> ExerciseSheetRecord.Tombstone(syncId, updatedAt)
                false -> {
                    val value = metadata ?: return null
                    if (!hasEmptyMarker && muscleLoads.isEmpty()) return null
                    ExerciseSheetRecord.Snapshot(
                        syncId = syncId,
                        updatedAt = updatedAt,
                        name = value.name,
                        muscleGroup = value.muscleGroup,
                        type = value.type,
                        isCustom = value.isCustom,
                        muscleLoads = muscleLoads.toMap(),
                    )
                }
                null -> null
            }
        }

        private fun reject(): Boolean {
            invalid = true
            return false
        }
    }

    fun parse(rows: List<List<String>>): ParsedExerciseSheetRows {
        var skippedRows = 0
        val versions = LinkedHashMap<String, LinkedHashMap<Long, SnapshotBuilder>>()
        rows.forEach { row ->
            if (row.isBlankSheetRow() || row.sheetCell(EXERCISE_ID) == "exercise_id") return@forEach
            val syncId = canonicalSheetUuidOrNull(row.sheetCell(EXERCISE_ID))
            val updatedAt = row.sheetCell(UPDATED_AT).toSheetLongOrNull()?.takeIf { it > 0 }
            if (syncId == null || updatedAt == null) {
                skippedRows++
                return@forEach
            }
            val builder = versions
                .getOrPut(syncId) { LinkedHashMap() }
                .getOrPut(updatedAt) { SnapshotBuilder(syncId, updatedAt) }
            if (!builder.accept(row)) skippedRows++
        }
        val records = versions.values.mapNotNull { snapshots ->
            snapshots.values.maxByOrNull(SnapshotBuilder::updatedAt)?.toRecord()
        }
        return ParsedExerciseSheetRows(records, skippedRows)
    }

    private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? =
        enumValues<T>().firstOrNull { it.name.equals(this, ignoreCase = true) }

    private fun List<String>.hasContentFrom(index: Int): Boolean = drop(index).any(String::isNotBlank)

    private const val EXERCISE_ID = 0
    private const val UPDATED_AT = 1
    private const val IS_DELETED = 2
    private const val EXERCISE_NAME = 3
    private const val MUSCLE_GROUP = 4
    private const val TYPE = 5
    private const val IS_CUSTOM = 6
    private const val MUSCLE = 7
    private const val CONTRIBUTION = 8
    private const val MIN_CONTRIBUTION = 0
    private const val MAX_CONTRIBUTION = 100
}
