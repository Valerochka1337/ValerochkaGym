package com.valerochka1337.valerochkagym.data.backend

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.sqlite.db.SupportSQLiteDatabase
import com.valerochka1337.valerochkagym.data.db.EquipmentCatalog
import com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Entity(tableName = "catalog_state")
data class CatalogStateEntity(
    @PrimaryKey val id: Int = 1,
    val revision: Long = 0,
    val active: Boolean = false,
    val bootstrapped: Boolean = false,
    val applying: Boolean = false,
    val pendingSnapshot: String? = null,
    val originalOutbox: String? = null,
    val bootstrapSnapshot: String? = null,
)

@Entity(tableName = "catalog_records")
data class CatalogRecordEntity(@PrimaryKey val key: String, val recordJson: String)

@Entity(tableName = "catalog_equipment")
data class CatalogEquipmentEntity(
    @PrimaryKey val id: String,
    val revision: Long,
    val archived: Boolean,
    val payload: String,
)

@Serializable
data class StandardRecord(
    val kind: String,
    val id: String,
    val revision: Long,
    val archived: Boolean = false,
    val payload: JsonObject,
) {
  val key
    get() = "$kind:$id"

  fun cloud() = CloudRecord(kind, id, revision, false, payload)
}

@Serializable
data class StandardSnapshot(
    val active: Boolean,
    val revision: Long,
    val records: List<StandardRecord>,
    val equipment: List<StandardRecord>,
)

object CatalogSchema {
  fun create(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS catalog_state (id INTEGER NOT NULL,revision INTEGER NOT NULL,active INTEGER NOT NULL,bootstrapped INTEGER NOT NULL,applying INTEGER NOT NULL,pendingSnapshot TEXT,originalOutbox TEXT,bootstrapSnapshot TEXT,PRIMARY KEY(id))"
    )
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS catalog_records (`key` TEXT NOT NULL,recordJson TEXT NOT NULL,PRIMARY KEY(`key`))"
    )
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS catalog_equipment (id TEXT NOT NULL,revision INTEGER NOT NULL,archived INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(id))"
    )
    install(db)
  }

  fun install(db: SupportSQLiteDatabase) {
    db.execSQL(
        "INSERT OR IGNORE INTO catalog_state(id,revision,active,bootstrapped,applying) VALUES(1,0,0,0,0)"
    )
    for ((table, key) in listOf("exercises" to "id", "gyms" to "id", "routines" to "id")) {
      for (op in listOf("UPDATE", "DELETE")) db.execSQL(
          "CREATE TRIGGER IF NOT EXISTS standard_${table}_${op.lowercase()} BEFORE $op ON $table WHEN OLD.origin='STANDARD' AND (SELECT applying FROM catalog_state WHERE id=1)=0 BEGIN SELECT RAISE(ABORT,'Создайте личную копию стандартного объекта'); END"
      )
    }
    for ((table, parent, column) in
        listOf(
            Triple("exercise_muscles", "exercises", "exerciseId"),
            Triple("exercise_equipment", "exercises", "exerciseId"),
            Triple("gym_equipment", "gyms", "gymId"),
            Triple("gym_exercises", "gyms", "gymId"),
            Triple("routine_exercises", "routines", "routineId"),
            Triple("routine_gyms", "routines", "routineId"),
        )) {
      for (op in listOf("INSERT", "UPDATE", "DELETE")) {
        val owner = if (op == "INSERT") "NEW" else "OLD"
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS standard_${table}_${op.lowercase()} BEFORE $op ON $table WHEN (SELECT origin FROM $parent WHERE id=$owner.$column)='STANDARD' AND (SELECT applying FROM catalog_state WHERE id=1)=0 BEGIN SELECT RAISE(ABORT,'Создайте личную копию стандартного объекта'); END"
        )
      }
    }
    // Only a truly empty reference table uses APK data. Opening an existing database never
    // updates downloaded names, aliases or coverage.
    val empty = db.query("SELECT 1 FROM catalog_equipment LIMIT 1").use { !it.moveToFirst() }
    if (empty)
        EquipmentCatalog.entries.forEach { e ->
          val payload = buildJsonObject {
            put("name", e.name)
            put("group", e.group)
            put("synonyms", JsonArray(e.synonyms.map(::JsonPrimitive)))
            put("provides", JsonArray(e.provides.map(::JsonPrimitive)))
          }
          db.execSQL(
              "INSERT OR IGNORE INTO catalog_equipment(id,revision,archived,payload) VALUES(?,0,0,?)",
              arrayOf(e.id, payload.toString()),
          )
        }
  }

  fun publishEquipment(db: SupportSQLiteDatabase) {
    val entries =
        db.query("SELECT id,archived,payload FROM catalog_equipment ORDER BY id").use { c ->
          buildList {
            while (c.moveToNext()) {
              val p = Json.parseToJsonElement(c.getString(2)).jsonObject
              add(
                  LocalEquipmentCatalog.Entry(
                      EquipmentCatalog.Equipment(
                          c.getString(0),
                          p.getValue("name").jsonPrimitive.content,
                          p.getValue("group").jsonPrimitive.content,
                          p.getValue("synonyms").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                          p.getValue("provides").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                      ),
                      c.getInt(1) != 0,
                  )
              )
            }
          }
        }
    LocalEquipmentCatalog.publish(entries)
  }
}
