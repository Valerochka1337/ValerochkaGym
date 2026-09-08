package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog

/** Контракт append-only листа `Gyms` (A:G), где каждая версия содержит полный состав зала. */
object GymSheetRowMapper {
  const val SHEET_NAME = "Gyms"
  const val RANGE = "Gyms!A:G"

  val HEADER_ROW: List<String> =
      listOf(
          "gym_id",
          "updated_at",
          "is_deleted",
          "gym_name",
          "exercise_id",
          "inventory_configured",
          "equipment_id",
      )

  fun rows(record: GymSheetRecord): List<List<Any?>> =
      when (record) {
        is GymSheetRecord.Snapshot -> snapshotRows(record)
        is GymSheetRecord.Tombstone -> listOf(deletion(record.syncId, record.updatedAt))
      }

  fun deletion(syncId: String, updatedAt: Long): List<Any?> {
    val canonicalId = requireCanonicalSheetUuid(syncId, "gym_id")
    requireSheetVersion(updatedAt)
    return listOf(canonicalId, updatedAt, "true", "", "", "", "")
  }

  private fun snapshotRows(snapshot: GymSheetRecord.Snapshot): List<List<Any?>> {
    val canonicalId = requireCanonicalSheetUuid(snapshot.syncId, "gym_id")
    requireSheetVersion(snapshot.updatedAt)
    require(snapshot.name.isNotBlank()) { "gym_name не должен быть пустым" }
    val base = listOf<Any?>(canonicalId, snapshot.updatedAt, "false", snapshot.name)
    return if (snapshot.inventoryConfigured) {
      snapshot.equipmentIds
          .sorted()
          .ifEmpty { listOf("") }
          .map { equipment -> base + listOf("", "true", equipment) }
    } else {
      val exerciseIds =
          snapshot.exerciseSyncIds
              .map { requireCanonicalSheetUuid(it, "exercise_id") }
              .toSortedSet()
      if (exerciseIds.isEmpty()) listOf(base + listOf("", "false", ""))
      else exerciseIds.map { exerciseId -> base + listOf(exerciseId, "false", "") }
    }
  }
}

/** Строгий разбор полных снимков залов с выбором максимального `updated_at`. */
object GymSheetRowParser {
  private data class SnapshotBuilder(
      val syncId: String,
      val updatedAt: Long,
      var isDeleted: Boolean? = null,
      var name: String? = null,
      var hasEmptyMarker: Boolean = false,
      var inventoryConfigured: Boolean? = null,
      val equipmentIds: MutableSet<String> = linkedSetOf(),
      val exerciseSyncIds: MutableSet<String> = linkedSetOf(),
      var hasInvalidEquipment: Boolean = false,
      var invalid: Boolean = false,
  ) {
    fun accept(row: List<String>): Boolean {
      val deleted = row.sheetCell(IS_DELETED).toSheetBooleanOrNull() ?: return reject()
      isDeleted?.let { previous -> if (previous != deleted) return reject() }
      isDeleted = deleted

      if (deleted) {
        if (
            row.sheetCell(GYM_NAME).isNotEmpty() ||
                row.sheetCell(EXERCISE_ID).isNotEmpty() ||
                row.sheetCell(INVENTORY_CONFIGURED).isNotEmpty() ||
                row.sheetCell(EQUIPMENT_ID).isNotEmpty()
        )
            return rejectEquipment()
        if (name != null || hasEmptyMarker || exerciseSyncIds.isNotEmpty()) return reject()
        return true
      }

      val incomingName = row.sheetCell(GYM_NAME).takeIf(String::isNotEmpty) ?: return reject()
      name?.let { previous -> if (previous != incomingName) return reject() }
      name = incomingName
      val rawConfigured = row.sheetCell(INVENTORY_CONFIGURED)
      val configured =
          if (rawConfigured.isEmpty()) false
          else rawConfigured.toSheetBooleanOrNull() ?: return rejectEquipment()
      inventoryConfigured?.let { previous -> if (previous != configured) return rejectEquipment() }
      inventoryConfigured = configured
      val equipment = row.sheetCell(EQUIPMENT_ID)
      if (configured) {
        if (row.sheetCell(EXERCISE_ID).isNotEmpty()) return rejectEquipment()
        if (equipment.isEmpty()) {
          if (equipmentIds.isNotEmpty()) return rejectEquipment()
          hasEmptyMarker = true
        } else if (hasEmptyMarker || !LocalEquipmentCatalog.isKnown(equipment))
            return rejectEquipment()
        else equipmentIds += equipment
        return true
      }
      if (equipment.isNotEmpty()) return rejectEquipment()
      val exerciseCell = row.sheetCell(EXERCISE_ID)
      if (exerciseCell.isEmpty()) {
        if (exerciseSyncIds.isNotEmpty()) return reject()
        hasEmptyMarker = true
        return true
      }
      if (hasEmptyMarker) return reject()
      val exerciseId = canonicalSheetUuidOrNull(exerciseCell) ?: return reject()
      exerciseSyncIds += exerciseId
      return true
    }

    fun toRecord(): GymSheetRecord? {
      if (invalid) return null
      return when (isDeleted) {
        true -> GymSheetRecord.Tombstone(syncId, updatedAt)
        false ->
            GymSheetRecord.Snapshot(
                syncId = syncId,
                updatedAt = updatedAt,
                name = name ?: return null,
                exerciseSyncIds = exerciseSyncIds.toSet(),
                inventoryConfigured = inventoryConfigured ?: false,
                equipmentIds = equipmentIds.toSet(),
            )
        null -> null
      }
    }

    private fun reject(): Boolean {
      invalid = true
      return false
    }

    private fun rejectEquipment(): Boolean {
      hasInvalidEquipment = true
      return reject()
    }
  }

  fun parse(rows: List<List<String>>): ParsedGymSheetRows {
    var skippedRows = 0
    var hasInvalidEquipment = false
    val versions = LinkedHashMap<String, LinkedHashMap<Long, SnapshotBuilder>>()
    rows.forEach { row ->
      if (row.isBlankSheetRow() || row.sheetCell(GYM_ID) == "gym_id") return@forEach
      val syncId = canonicalSheetUuidOrNull(row.sheetCell(GYM_ID))
      val updatedAt = row.sheetCell(UPDATED_AT).toSheetLongOrNull()?.takeIf { it > 0 }
      if (syncId == null || updatedAt == null) {
        skippedRows++
        return@forEach
      }
      val builder =
          versions
              .getOrPut(syncId) { LinkedHashMap() }
              .getOrPut(updatedAt) { SnapshotBuilder(syncId, updatedAt) }
      val rawConfigured = row.sheetCell(INVENTORY_CONFIGURED)
      val configured = rawConfigured.toSheetBooleanOrNull()
      val equipment = row.sheetCell(EQUIPMENT_ID)
      if (
          (rawConfigured.isNotEmpty() && configured == null) ||
              (configured == true && row.sheetCell(EXERCISE_ID).isNotEmpty()) ||
              (configured != true && equipment.isNotEmpty()) ||
              (equipment.isNotEmpty() && !LocalEquipmentCatalog.isKnown(equipment))
      ) {
        hasInvalidEquipment = true
      }
      if (!builder.accept(row)) skippedRows++
      if (builder.hasInvalidEquipment) hasInvalidEquipment = true
    }
    val records =
        versions.values.mapNotNull { snapshots ->
          snapshots.values.maxByOrNull(SnapshotBuilder::updatedAt)?.toRecord()
        }
    return ParsedGymSheetRows(records, skippedRows, hasInvalidEquipment)
  }

  private const val GYM_ID = 0
  private const val UPDATED_AT = 1
  private const val IS_DELETED = 2
  private const val GYM_NAME = 3
  private const val EXERCISE_ID = 4
  private const val INVENTORY_CONFIGURED = 5
  private const val EQUIPMENT_ID = 6
}
