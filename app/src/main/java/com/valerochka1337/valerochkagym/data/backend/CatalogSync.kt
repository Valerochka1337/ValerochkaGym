package com.valerochka1337.valerochkagym.data.backend

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Catalog and personal acknowledgements never share a baseline or a revision. */
class CatalogSync(private val database: GymDatabase, private val api: BackendTransport) {
  private val db
    get() = database.openHelper.writableDatabase

  fun revision(): Long =
      db.query("SELECT revision FROM catalog_state WHERE id=1").use {
        if (it.moveToFirst()) it.getLong(0) else 0
      }

  private fun active() =
      db.query("SELECT 1 FROM workouts WHERE finishedAt IS NULL LIMIT 1").use { it.moveToFirst() }

  suspend fun refresh(resolve: String? = null, ownerGuard: (() -> Unit)? = null) {
    val snapshot =
        try {
          api.json.decodeFromJsonElement<StandardSnapshot>(api.public("GET", "/catalog"))
        } catch (e: BackendException) {
          // Rolling deployment: a v2 client can finish personal sync before the catalog backend
          // lands.
          if (e.status == 404 && revision() == 0L) return else throw e
        }
    var conflict = false
    database.withTransaction {
      ownerGuard?.invoke()
      if (active())
          throw BackendException(
              409,
              "workout_active",
              "Завершите тренировку перед обновлением каталога",
          )
      val local = PortableData(db).snapshot(includeStandard = true)
      val bootstrap =
          db.query("SELECT bootstrapSnapshot FROM catalog_state WHERE id=1").use {
            if (it.moveToFirst() && !it.isNull(0))
                api.json.parseToJsonElement(it.getString(0)).jsonObject
            else JsonObject(emptyMap())
          }
      if (snapshot.active) {
        val sharedKeys = snapshot.records.map { it.key }.toSet()
        // A clean restore must not upload unused APK placeholders whose IDs differ from
        // the source account's legacy UUIDs. Offline edits and referenced placeholders stay.
        bootstrap.forEach { (key, payload) ->
          if (key.startsWith("exercise:") && key !in sharedKeys && local[key] == payload) {
            db.execSQL(
                "DELETE FROM exercises WHERE syncId=? AND origin='PERSONAL' AND id NOT IN (SELECT exerciseId FROM gym_exercises UNION SELECT exerciseId FROM routine_exercises UNION SELECT exerciseId FROM workout_exercises)",
                arrayOf(key.substringAfter(':')),
            )
          }
        }
      }
      val base =
          db.query("SELECT recordJson FROM backend_baseline").use { c ->
            buildMap {
              while (c.moveToNext()) {
                val r = api.json.decodeFromString<CloudRecord>(c.getString(0))
                put(r.key, r)
              }
            }
          }
      val common =
          db.query("SELECT `key` FROM catalog_records").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
          }
      val conflicts =
          snapshot.records.filter { r ->
            r.key !in common &&
                local[r.key] != r.payload &&
                local[r.key] != base[r.key]?.payload &&
                (base[r.key] != null ||
                    local[r.key]?.get("isCustom") != JsonPrimitive(false) ||
                    bootstrap[r.key] != local[r.key])
          }
      if (conflicts.isNotEmpty() && resolve == null) {
        // Commit the transition journal, including the exact pre-migration request, before
        // reporting the conflict. Neither the source rows nor outbox are changed yet.
        db.execSQL(
            "UPDATE catalog_state SET pendingSnapshot=?,originalOutbox=COALESCE(originalOutbox,(SELECT requestJson FROM backend_outbox WHERE id=1)) WHERE id=1",
            arrayOf(api.json.encodeToString(snapshot)),
        )
        conflict = true
        return@withTransaction
      }
      if (resolve == "local")
          conflicts.forEach { r ->
            (local[r.key] ?: base[r.key]?.payload)?.let { payload -> personalCopy(r.kind, payload) }
          }
      val pending =
          db.query("SELECT owner,requestJson FROM backend_outbox WHERE id=1").use { c ->
            if (c.moveToFirst())
                c.getString(0) to api.json.decodeFromString<CloudPush>(c.getString(1))
            else null
          }
      val keys = snapshot.records.map { it.key }.toSet()
      db.execSQL("UPDATE catalog_state SET applying=1 WHERE id=1")
      val changed =
          snapshot.records.filter { r ->
            db.query("SELECT recordJson FROM catalog_records WHERE `key`=?", arrayOf(r.key)).use { c
              ->
              !c.moveToFirst() || api.json.decodeFromString<StandardRecord>(c.getString(0)) != r
            }
          }
      PortableData(db).apply(changed.map { it.cloud() }, emptyList())
      snapshot.records.forEach { r ->
        val table = table(r.kind)
        db.execSQL(
            "UPDATE $table SET origin='STANDARD',archived=? WHERE syncId=? AND (origin!='STANDARD' OR archived!=?)",
            arrayOf<Any>(if (r.archived) 1 else 0, r.id, if (r.archived) 1 else 0),
        )
        db.execSQL(
            "INSERT OR REPLACE INTO catalog_records(`key`,recordJson) VALUES(?,?)",
            arrayOf(r.key, api.json.encodeToString(r)),
        )
        db.execSQL("DELETE FROM backend_baseline WHERE `key`=?", arrayOf(r.key))
      }
      if (pending != null && pending.second.changes.any { "${it.kind}:${it.id}" in keys }) {
        db.execSQL(
            "UPDATE catalog_state SET originalOutbox=COALESCE(originalOutbox,?) WHERE id=1",
            arrayOf(api.json.encodeToString(pending.second)),
        )
        val remaining = pending.second.changes.filter { "${it.kind}:${it.id}" !in keys }
        if (remaining.isEmpty()) db.execSQL("DELETE FROM backend_outbox WHERE id=1")
        else
            db.execSQL(
                "UPDATE backend_outbox SET requestJson=? WHERE id=1",
                arrayOf(
                    api.json.encodeToString(
                        CloudPush(UUID.randomUUID().toString(), remaining, snapshot.revision)
                    )
                ),
            )
      }
      snapshot.equipment.forEach { e ->
        db.execSQL(
            "INSERT OR REPLACE INTO catalog_equipment(id,revision,archived,payload) VALUES(?,?,?,?)",
            arrayOf<Any>(e.id, e.revision, if (e.archived) 1 else 0, e.payload.toString()),
        )
      }
      db.execSQL(
          "UPDATE catalog_state SET revision=?,active=?,pendingSnapshot=NULL,applying=0,bootstrapped=1 WHERE id=1",
          arrayOf(snapshot.revision, if (snapshot.active) 1 else 0),
      )
      ownerGuard?.invoke()
    }
    if (conflict)
        throw BackendException(
            409,
            "catalog_transition_required",
            "Локальные изменения стандартных объектов сохранены. Создайте личные копии или примите стандартные версии в настройках аккаунта",
        )
    CatalogSchema.publishEquipment(db)
  }

  /** Caller owns the transaction. References keep their original shared targets. */
  fun personalCopy(kind: String, payload: JsonObject): String {
    val id = UUID.randomUUID().toString()
    val body =
        JsonObject(
            payload +
                mapOf(
                    "name" to
                        JsonPrimitive(payload.getValue("name").jsonPrimitive.content + " · копия"),
                    "updatedAt" to JsonPrimitive(System.currentTimeMillis()),
                ) +
                if (kind == "exercise") mapOf("isCustom" to JsonPrimitive(true)) else emptyMap()
        )
    PortableData(db).apply(listOf(CloudRecord(kind, id, 0, false, body)), emptyList())
    return id
  }

  companion object {
    fun table(kind: String) =
        when (kind) {
          "exercise" -> "exercises"
          "gym" -> "gyms"
          "routine" -> "routines"
          else -> error("Invalid catalog kind")
        }
  }
}
