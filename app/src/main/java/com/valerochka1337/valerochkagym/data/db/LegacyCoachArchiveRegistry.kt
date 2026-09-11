package com.valerochka1337.valerochkagym.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Raw, inert preservation for the one confirmed pre-rebase Coach schema. These tables are not Room
 * entities and are deliberately never read by current coach code or portable sync.
 */
internal object LegacyCoachArchiveRegistry {
  private data class Column(
      val name: String,
      val type: String,
      val notNull: Boolean,
      val pk: Int,
      val default: String? = null,
  )

  private data class ForeignKey(val referenceTable: String, val from: String, val to: String)

  private data class Table(
      val current: String,
      val archive: String,
      val columns: List<Column>,
      val foreignKeys: Set<ForeignKey> = emptySet(),
  )

  private val tables =
      listOf(
          Table(
              "coach_sessions",
              "legacy_live_coach_v1_sessions",
              listOf(
                  Column("workoutId", "TEXT", true, 1),
                  Column("accountId", "TEXT", false, 0),
                  Column("deviceId", "TEXT", true, 0),
                  Column("revision", "INTEGER", true, 0),
                  Column("rulesVersion", "TEXT", true, 0),
                  Column("status", "TEXT", true, 0),
                  Column("sessionJson", "TEXT", true, 0),
                  Column("updatedAt", "INTEGER", true, 0),
              ),
              setOf(ForeignKey("workouts", "workoutId", "id")),
          ),
          Table(
              "coach_proposals",
              "legacy_live_coach_v1_proposals",
              listOf(
                  Column("id", "TEXT", true, 1),
                  Column("workoutId", "TEXT", true, 0),
                  Column("snapshotId", "TEXT", true, 0),
                  Column("decisionJson", "TEXT", true, 0),
                  Column("inverseJson", "TEXT", true, 0),
                  Column("affectedRevisionsJson", "TEXT", true, 0),
                  Column("status", "TEXT", true, 0),
                  Column("createdAt", "INTEGER", true, 0),
                  Column("appliedAt", "INTEGER", false, 0),
              ),
              setOf(ForeignKey("workouts", "workoutId", "id")),
          ),
          Table(
              "coach_journal",
              "legacy_live_coach_v1_journal",
              listOf(
                  Column("id", "TEXT", true, 1),
                  Column("workoutId", "TEXT", true, 0),
                  Column("deviceId", "TEXT", true, 0),
                  Column("createdAt", "INTEGER", true, 0),
                  Column("payloadJson", "TEXT", true, 0),
                  Column("synced", "INTEGER", true, 0),
              ),
              setOf(ForeignKey("workouts", "workoutId", "id")),
          ),
          Table(
              "coach_sync_state",
              "legacy_live_coach_v1_sync_state",
              listOf(
                  Column("owner", "TEXT", true, 1),
                  Column("watermark", "INTEGER", true, 0, "0"),
                  Column("cursor", "TEXT", false, 0),
              ),
          ),
          Table(
              "coach_weight_exclusions",
              "legacy_live_coach_v1_weight_exclusions",
              listOf(
                  Column("gymId", "INTEGER", true, 1),
                  Column("exerciseId", "TEXT", true, 2),
                  Column("weightKg", "REAL", true, 3),
                  Column("createdAt", "INTEGER", true, 0),
              ),
              setOf(
                  ForeignKey("gyms", "gymId", "id"),
                  ForeignKey("exercises", "exerciseId", "syncId"),
              ),
          ),
      )

  private val legacyIndexes =
      listOf(
          "index_coach_journal_synced",
          "index_coach_journal_synced_createdAt",
          "index_coach_journal_workoutId",
          "index_coach_proposals_status",
          "index_coach_proposals_workoutId",
          "index_coach_sessions_status",
          "index_coach_sessions_workoutId",
          "index_coach_weight_exclusions_exerciseId",
          "index_coach_weight_exclusions_gymId",
      )

  /** Returns false for every partial or unknown `coach_*` shape, leaving Room to fail safely. */
  fun archiveConfirmedDeviceV17(db: SupportSQLiteDatabase): Boolean {
    if (!tables.all { matches(db, it) }) return false
    legacyIndexes.forEach { db.execSQL("DROP INDEX IF EXISTS `$it`") }
    tables.forEach { db.execSQL("ALTER TABLE `${it.current}` RENAME TO `${it.archive}`") }
    return true
  }

  /** Explicit lifecycle cleanup for inert archives which do not all cascade from workouts. */
  fun purge(db: SupportSQLiteDatabase) {
    tables.forEach { db.execSQL("DROP TABLE IF EXISTS `${it.archive}`") }
  }

  internal fun archiveTableNames(): List<String> = tables.map { it.archive }

  private fun matches(db: SupportSQLiteDatabase, table: Table): Boolean {
    if (!exists(db, table.current) || exists(db, table.archive)) return false
    val found = mutableListOf<Column>()
    db.query("PRAGMA table_info(`${table.current}`)").use { rows ->
      while (rows.moveToNext()) {
        found +=
            Column(
                rows.getString(1),
                rows.getString(2),
                rows.getInt(3) != 0,
                rows.getInt(5),
                if (rows.isNull(4)) null else rows.getString(4),
            )
      }
    }
    if (found != table.columns) return false
    val foreignKeys = mutableSetOf<ForeignKey>()
    db.query("PRAGMA foreign_key_list(`${table.current}`)").use { rows ->
      while (rows.moveToNext()) {
        if (rows.getString(5) != "NO ACTION" || rows.getString(6) != "CASCADE") return false
        foreignKeys += ForeignKey(rows.getString(2), rows.getString(3), rows.getString(4))
      }
    }
    return foreignKeys == table.foreignKeys
  }

  private fun exists(db: SupportSQLiteDatabase, name: String): Boolean =
      db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use {
        it.moveToFirst()
      }
}
