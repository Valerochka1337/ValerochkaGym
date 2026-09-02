package com.valerochka1337.valerochkagym.domain

/** Append-only definition records for `ExerciseVariants` (A:F). */
data class ExerciseVariantSheetRecord(
    val syncId: String,
    val exerciseSyncId: String,
    val updatedAt: Long,
    val name: String,
    val isArchived: Boolean,
)

object ExerciseVariantSheetRowMapper {
    const val SHEET_NAME = "ExerciseVariants"
    const val RANGE = "ExerciseVariants!A:F"
    val HEADER_ROW = listOf("variant_id", "exercise_id", "updated_at", "variant_name", "is_archived", "is_deleted")

    fun row(record: ExerciseVariantSheetRecord): List<Any?> = listOf(
        requireCanonicalSheetUuid(record.syncId, "variant_id"),
        requireCanonicalSheetUuid(record.exerciseSyncId, "exercise_id"),
        record.updatedAt.also(::requireSheetVersion),
        record.name.trim().also { require(it.isNotEmpty()) { "variant_name не должен быть пустым" } },
        record.isArchived.toString(),
        "false",
    )
}

object ExerciseVariantSheetRowParser {
    fun parse(rows: List<List<String>>): List<ExerciseVariantSheetRecord> = rows
        .asSequence()
        .filterNot { it.isBlankSheetRow() || it.sheetCell(0) == "variant_id" }
        .mapNotNull { row ->
            val id = canonicalSheetUuidOrNull(row.sheetCell(0)) ?: return@mapNotNull null
            val owner = canonicalSheetUuidOrNull(row.sheetCell(1)) ?: return@mapNotNull null
            val updatedAt = row.sheetCell(2).toSheetLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val name = row.sheetCell(3).takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val archived = row.sheetCell(4).toSheetBooleanOrNull() ?: return@mapNotNull null
            if (row.sheetCell(5).toSheetBooleanOrNull() == true) return@mapNotNull null
            ExerciseVariantSheetRecord(id, owner, updatedAt, name, archived)
        }
        .groupBy(ExerciseVariantSheetRecord::syncId)
        .mapNotNull { (_, records) -> records.maxByOrNull(ExerciseVariantSheetRecord::updatedAt) }
        .toList()
}
