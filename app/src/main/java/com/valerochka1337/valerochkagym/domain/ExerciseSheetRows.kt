package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.MuscleRole

/** Append-only Exercises contract. A:I remains legacy-compatible; J marks canonical roles. */
object ExerciseSheetRowMapper {
    const val SHEET_NAME = "Exercises"
    const val RANGE = "Exercises!A:J"

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
        "model_version",
    )

    fun rows(record: ExerciseSheetRecord): List<List<Any?>> = when (record) {
        is ExerciseSheetRecord.Snapshot -> snapshotRows(record)
        is ExerciseSheetRecord.Tombstone -> listOf(deletion(record.syncId, record.updatedAt))
    }

    fun deletion(syncId: String, updatedAt: Long): List<Any?> {
        val canonicalId = requireCanonicalSheetUuid(syncId, "exercise_id")
        requireSheetVersion(updatedAt)
        return listOf(canonicalId, updatedAt, "true", "", "", "", "", "", "", MODEL_VERSION.toString())
    }

    private fun snapshotRows(snapshot: ExerciseSheetRecord.Snapshot): List<List<Any?>> {
        val canonicalId = requireCanonicalSheetUuid(snapshot.syncId, "exercise_id")
        requireSheetVersion(snapshot.updatedAt)
        require(snapshot.name.isNotBlank()) { "exercise_name не должен быть пустым" }
        snapshot.muscleLoads.forEach { (_, contribution) ->
            require(contribution in setOf(100, 50, 0)) {
                "contribution должен быть канонической ролью 100, 50 или 0"
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
        if (snapshot.muscleLoads.isEmpty()) return listOf(base + listOf("", "", MODEL_VERSION))
        return Muscle.entries.mapNotNull { muscle ->
            snapshot.muscleLoads[muscle]?.let { contribution ->
                base + listOf(muscle.name, contribution, MODEL_VERSION)
            }
        }
    }

    const val MODEL_VERSION = 2
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
        var sawLegacyRow: Boolean = false,
        var canonicalRoles: Boolean? = null,
        var sawLegacyChest: Boolean = false,
        val muscleLoads: MutableMap<Muscle, Int> = linkedMapOf(),
        var invalid: Boolean = false,
    ) {
        fun accept(row: List<String>): Boolean {
            val deleted = row.sheetCell(IS_DELETED).toSheetBooleanOrNull() ?: return reject()
            isDeleted?.let { previous -> if (previous != deleted) return reject() }
            isDeleted = deleted

            if (deleted) {
                if (row.drop(EXERCISE_NAME).take(CONTRIBUTION - EXERCISE_NAME + 1).any(String::isNotBlank)) return reject()
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

            val version = row.sheetCell(MODEL_VERSION).takeIf(String::isNotBlank)
            val canonical = when (version) {
                null -> false
                ExerciseSheetRowMapper.MODEL_VERSION.toString() -> true
                else -> return reject()
            }
            canonicalRoles?.let { previous -> if (previous != canonical) return reject() }
            canonicalRoles = canonical

            val muscleCell = row.sheetCell(MUSCLE)
            val contributionCell = row.sheetCell(CONTRIBUTION)
            if (muscleCell.isEmpty() && contributionCell.isEmpty()) {
                if (muscleLoads.isNotEmpty()) return reject()
                hasEmptyMarker = true
                return true
            }
            if (muscleCell.isEmpty() || contributionCell.isEmpty() || hasEmptyMarker) return reject()
            val contribution = contributionCell.toSheetIntOrNull()
                ?.takeIf { it in MIN_CONTRIBUTION..MAX_CONTRIBUTION } ?: return reject()
            if (!canonical && contribution == 0) {
                if (!muscleCell.equals("CHEST", true) && muscleCell.toEnumOrNull<Muscle>() == null) return reject()
                sawLegacyRow = true
                return true // old zero meant absent, but still denotes a valid empty snapshot
            }
            val loads = (if (canonical) canonicalLoads(muscleCell, contribution)
            else legacyLoads(muscleCell, contribution)) ?: return reject()
            if (loads.isEmpty()) {
                return true
            }
            loads.forEach { (muscle, canonicalContribution) ->
                val previous = muscleLoads[muscle]
                if (previous != null && previous != canonicalContribution) return reject()
                muscleLoads[muscle] = canonicalContribution
            }
            return true
        }

        fun toRecord(): ExerciseSheetRecord? {
            if (invalid) return null
            return when (isDeleted) {
                true -> ExerciseSheetRecord.Tombstone(syncId, updatedAt)
                false -> {
                    val value = metadata ?: return null
                    if (!hasEmptyMarker && !sawLegacyRow && muscleLoads.isEmpty()) return null
                    ExerciseSheetRecord.Snapshot(
                        syncId = syncId,
                        updatedAt = updatedAt,
                        name = value.name,
                        muscleGroup = value.muscleGroup,
                        type = value.type,
                        isCustom = value.isCustom,
                        muscleLoads = muscleLoads.toMap(),
                        needsMuscleMapReview = sawLegacyChest,
                    )
                }
                null -> null
            }
        }

        private fun reject(): Boolean {
            invalid = true
            return false
        }

        /** Old CHEST is input-only and is intentionally expanded before persistence. */
        private fun legacyLoads(name: String, contribution: Int): List<Pair<Muscle, Int>>? {
            val role = MuscleRole.fromContribution(contribution)
                ?: MuscleRole.fromLegacyContribution(contribution)
                ?: return if (contribution == 0 && name.equals("CHEST", ignoreCase = true)) emptyList() else null
            if (name.equals("CHEST", ignoreCase = true)) {
                sawLegacyChest = true
                return listOf(
                    Muscle.UPPER_CHEST to role.contribution,
                    Muscle.LOWER_CHEST to role.contribution,
                )
            }
            val muscle = name.toEnumOrNull<Muscle>() ?: return null
            return listOf(muscle to role.contribution)
        }

        private fun canonicalLoads(name: String, contribution: Int): List<Pair<Muscle, Int>>? {
            if (contribution !in setOf(100, 50, 0)) return null
            val muscle = name.toEnumOrNull<Muscle>() ?: return null
            return listOf(muscle to contribution)
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
    private const val MODEL_VERSION = 9
    private const val MIN_CONTRIBUTION = 0
    private const val MAX_CONTRIBUTION = 100
}
