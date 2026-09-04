package com.valerochka1337.valerochkagym.domain

/** Контракт append-only листа `RoutineGyms` (A:D), где строка без gym_id означает пустой набор. */
object RoutineGymsSheetRowMapper {
  const val SHEET_NAME = "RoutineGyms"
  const val RANGE = "RoutineGyms!A:D"

  val HEADER_ROW: List<String> =
      listOf(
          "routine_id",
          "updated_at",
          "is_deleted",
          "gym_id",
      )

  fun rows(record: RoutineGymsSheetRecord): List<List<Any?>> =
      when (record) {
        is RoutineGymsSheetRecord.Snapshot -> snapshotRows(record)
        is RoutineGymsSheetRecord.Tombstone ->
            listOf(deletion(record.routineSyncId, record.updatedAt))
      }

  fun deletion(routineSyncId: String, updatedAt: Long): List<Any?> {
    val canonicalId = requireCanonicalSheetUuid(routineSyncId, "routine_id")
    requireSheetVersion(updatedAt)
    return listOf(canonicalId, updatedAt, "true", "")
  }

  private fun snapshotRows(snapshot: RoutineGymsSheetRecord.Snapshot): List<List<Any?>> {
    val canonicalId = requireCanonicalSheetUuid(snapshot.routineSyncId, "routine_id")
    requireSheetVersion(snapshot.updatedAt)
    val gymIds = snapshot.gymSyncIds.map { requireCanonicalSheetUuid(it, "gym_id") }.toSortedSet()
    val base = listOf<Any?>(canonicalId, snapshot.updatedAt, "false")
    return if (gymIds.isEmpty()) {
      listOf(base + "")
    } else {
      gymIds.map { gymId -> base + gymId }
    }
  }
}

/** Строгий разбор полных снимков привязок программы к залам. */
object RoutineGymsSheetRowParser {
  private data class SnapshotBuilder(
      val routineSyncId: String,
      val updatedAt: Long,
      var isDeleted: Boolean? = null,
      var hasEmptyMarker: Boolean = false,
      val gymSyncIds: MutableSet<String> = linkedSetOf(),
      var invalid: Boolean = false,
  ) {
    fun accept(row: List<String>): Boolean {
      val deleted = row.sheetCell(IS_DELETED).toSheetBooleanOrNull() ?: return reject()
      isDeleted?.let { previous -> if (previous != deleted) return reject() }
      isDeleted = deleted
      val gymCell = row.sheetCell(GYM_ID)

      if (deleted) {
        if (gymCell.isNotEmpty() || hasEmptyMarker || gymSyncIds.isNotEmpty()) return reject()
        return true
      }

      if (gymCell.isEmpty()) {
        if (gymSyncIds.isNotEmpty()) return reject()
        hasEmptyMarker = true
        return true
      }
      if (hasEmptyMarker) return reject()
      val gymId = canonicalSheetUuidOrNull(gymCell) ?: return reject()
      gymSyncIds += gymId
      return true
    }

    fun toRecord(): RoutineGymsSheetRecord? {
      if (invalid) return null
      return when (isDeleted) {
        true -> RoutineGymsSheetRecord.Tombstone(routineSyncId, updatedAt)
        false ->
            RoutineGymsSheetRecord.Snapshot(
                routineSyncId = routineSyncId,
                updatedAt = updatedAt,
                gymSyncIds = gymSyncIds.toSet(),
            )
        null -> null
      }
    }

    private fun reject(): Boolean {
      invalid = true
      return false
    }
  }

  fun parse(rows: List<List<String>>): ParsedRoutineGymsSheetRows {
    var skippedRows = 0
    val versions = LinkedHashMap<String, LinkedHashMap<Long, SnapshotBuilder>>()
    rows.forEach { row ->
      if (row.isBlankSheetRow() || row.sheetCell(ROUTINE_ID) == "routine_id") return@forEach
      val routineSyncId = canonicalSheetUuidOrNull(row.sheetCell(ROUTINE_ID))
      val updatedAt = row.sheetCell(UPDATED_AT).toSheetLongOrNull()?.takeIf { it > 0 }
      if (routineSyncId == null || updatedAt == null) {
        skippedRows++
        return@forEach
      }
      val builder =
          versions
              .getOrPut(routineSyncId) { LinkedHashMap() }
              .getOrPut(updatedAt) { SnapshotBuilder(routineSyncId, updatedAt) }
      if (!builder.accept(row)) skippedRows++
    }
    val records =
        versions.values.mapNotNull { snapshots ->
          snapshots.values.maxByOrNull(SnapshotBuilder::updatedAt)?.toRecord()
        }
    return ParsedRoutineGymsSheetRows(records, skippedRows)
  }

  private const val ROUTINE_ID = 0
  private const val UPDATED_AT = 1
  private const val IS_DELETED = 2
  private const val GYM_ID = 3
}
