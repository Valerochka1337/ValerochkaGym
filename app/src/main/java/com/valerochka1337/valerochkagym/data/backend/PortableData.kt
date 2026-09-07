package com.valerochka1337.valerochkagym.data.backend

import android.content.ContentValues
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID
import kotlinx.serialization.json.*

/** Portable aggregate mapping. Room row IDs stay on this device; all wire references use UUIDs. */
class PortableData(private val db: SupportSQLiteDatabase) {
  private val json = Json {
    ignoreUnknownKeys = false
    explicitNulls = true
    encodeDefaults = true
  }
  private val booleans =
      setOf("isCustom", "needsMuscleMapReview", "inventoryConfigured", "isCompleted")
  private val localFields = setOf("id", "syncId", "uploadStatus", "uploadError")

  private fun rows(
      table: String,
      where: String = "",
      args: Array<out Any?> = emptyArray(),
  ): List<JsonObject> =
      db.query("SELECT * FROM $table $where", args).use { c ->
        buildList {
          while (c.moveToNext()) add(
              buildJsonObject {
                c.columnNames.forEachIndexed { i, name ->
                  put(
                      name,
                      when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> JsonNull
                        Cursor.FIELD_TYPE_INTEGER ->
                            if (name in booleans) JsonPrimitive(c.getLong(i) != 0L)
                            else JsonPrimitive(c.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(i))
                        else -> JsonPrimitive(c.getString(i))
                      },
                  )
                }
              }
          )
        }
      }

  private fun JsonObject.s(key: String) = getValue(key).jsonPrimitive.content

  private fun JsonObject.portable(vararg exclude: String) =
      filterKeys { it !in localFields && it !in exclude }.toMutableMap()

  private fun array(values: List<JsonElement>) = JsonArray(values)

  fun snapshot(): Map<String, JsonObject> {
    val result = linkedMapOf<String, JsonObject>()
    val exercises = rows("exercises")
    val gyms = rows("gyms")
    val routines = rows("routines")
    val exerciseIds = exercises.associate { it.s("id") to it.s("syncId") }
    val gymIds = gyms.associate { it.s("id") to it.s("syncId") }
    val routineIds = routines.associate { it.s("id") to it.s("syncId") }
    fun links(
        table: String,
        ownerColumn: String,
        owner: String,
        target: String,
        map: Map<String, String>,
    ) =
        array(
            rows(table, "WHERE $ownerColumn=?", arrayOf(owner))
                .map { JsonPrimitive(map.getValue(it.s(target))) }
                .sortedBy { it.toString() }
        )
    exercises.forEach { e ->
      val n = e.portable()
      n["muscles"] =
          array(
              rows("exercise_muscles", "WHERE exerciseId=? ORDER BY muscle", arrayOf(e.s("id")))
                  .map { JsonObject(it - "exerciseId") }
          )
      n["equipmentIds"] =
          array(
              rows(
                      "exercise_equipment",
                      "WHERE exerciseId=? ORDER BY equipmentId",
                      arrayOf(e.s("id")),
                  )
                  .map { it.getValue("equipmentId") }
          )
      result["exercise:${e.s("syncId")}"] = JsonObject(n)
    }
    gyms.forEach { g ->
      val n = g.portable()
      n["exerciseIds"] = links("gym_exercises", "gymId", g.s("id"), "exerciseId", exerciseIds)
      n["equipmentIds"] =
          array(
              rows("gym_equipment", "WHERE gymId=? ORDER BY equipmentId", arrayOf(g.s("id"))).map {
                it.getValue("equipmentId")
              }
          )
      result["gym:${g.s("syncId")}"] = JsonObject(n)
    }
    routines.forEach { r ->
      val n = r.portable()
      n["gymIds"] = links("routine_gyms", "routineId", r.s("id"), "gymId", gymIds)
      n["exercises"] =
          array(
              rows(
                      "routine_exercises",
                      "WHERE routineId=? ORDER BY position,id",
                      arrayOf(r.s("id")),
                  )
                  .map { e ->
                    JsonObject(
                        e.portable("routineId", "plannedSetsJson").apply {
                          put("exerciseId", JsonPrimitive(exerciseIds.getValue(e.s("exerciseId"))))
                          put("plannedSets", json.parseToJsonElement(e.s("plannedSetsJson")))
                        }
                    )
                  }
          )
      result["routine:${r.s("syncId")}"] = JsonObject(n)
    }
    rows("workouts").forEach { w ->
      val n = w.portable()
      n["routineId"] =
          w["routineId"]
              ?.takeUnless { it == JsonNull }
              ?.let { routineIds[it.jsonPrimitive.content] }
              ?.let(::JsonPrimitive) ?: JsonNull
      n["gymIds"] = links("workout_gyms", "workoutId", w.s("id"), "gymId", gymIds)
      n["exercises"] =
          array(
              rows(
                      "workout_exercises",
                      "WHERE workoutId=? ORDER BY position,id",
                      arrayOf(w.s("id")),
                  )
                  .map { e ->
                    JsonObject(
                        e.portable("workoutId").apply {
                          put("exerciseId", JsonPrimitive(exerciseIds.getValue(e.s("exerciseId"))))
                          put(
                              "sets",
                              array(
                                  rows(
                                          "workout_sets",
                                          "WHERE workoutExerciseId=? ORDER BY setIndex,id",
                                          arrayOf(e.s("id")),
                                      )
                                      .map { JsonObject(it.portable("workoutExerciseId")) }
                              ),
                          )
                        }
                    )
                  }
          )
      result["workout:${w.s("id")}"] = JsonObject(n)
    }
    rows("body_measurements").forEach {
      result["measurement:${it.s("id")}"] = JsonObject(it.portable())
    }
    rows("scheduled_workouts").forEach { s ->
      val n = s.portable()
      n["routineId"] = JsonPrimitive(routineIds.getValue(s.s("routineId")))
      val id =
          UUID.nameUUIDFromBytes("ValerochkaGym.schedule:${s.s("calendarEventId")}".toByteArray())
              .toString()
      result["schedule:$id"] = JsonObject(n)
    }
    return result
  }

  private fun values(n: Map<String, JsonElement>): ContentValues =
      ContentValues().apply {
        n.forEach { (key, value) ->
          val p = value as? JsonPrimitive ?: error("Scalar required: $key")
          when {
            p == JsonNull -> putNull(key)
            p.isString -> put(key, p.content)
            p.booleanOrNull != null -> put(key, if (p.boolean) 1 else 0)
            p.longOrNull != null -> put(key, p.long)
            else -> put(key, p.double)
          }
        }
      }

  private fun insert(table: String, n: Map<String, JsonElement>): Long =
      db.insert(table, 0, values(n))

  private fun stable(table: String, id: String, n: Map<String, JsonElement>): Long {
    val old = rows(table, "WHERE syncId=?", arrayOf(id)).firstOrNull()
    return if (old == null) insert(table, n + mapOf("syncId" to JsonPrimitive(id)))
    else {
      val local = old.s("id").toLong()
      db.update(table, 0, values(n), "id=?", arrayOf(local))
      local
    }
  }

  private fun localId(table: String, id: JsonElement): JsonPrimitive =
      rows(table, "WHERE syncId=?", arrayOf(id.jsonPrimitive.content)).firstOrNull()?.get("id")
          as? JsonPrimitive ?: error("Missing $table reference")

  private fun replaceLinks(
      table: String,
      column: String,
      id: JsonPrimitive,
      rows: List<Map<String, JsonElement>>,
  ) {
    db.delete(table, "$column=?", arrayOf(id.content))
    rows.forEach { insert(table, it + mapOf(column to id)) }
  }

  /** Caller owns a Room transaction. Updates preserve local parent IDs and active section IDs. */
  fun apply(upserts: List<CloudRecord>, deletes: List<CloudRecord>) {
    val order = listOf("exercise", "gym", "routine", "workout", "measurement", "schedule")
    upserts
        .sortedBy { order.indexOf(it.kind) }
        .forEach { r ->
          val n = r.payload!!
          when (r.kind) {
            "exercise" -> {
              val id = JsonPrimitive(stable("exercises", r.id, n - "muscles" - "equipmentIds"))
              replaceLinks(
                  "exercise_muscles",
                  "exerciseId",
                  id,
                  n.getValue("muscles").jsonArray.map { it.jsonObject },
              )
              replaceLinks(
                  "exercise_equipment",
                  "exerciseId",
                  id,
                  n.getValue("equipmentIds").jsonArray.map { mapOf("equipmentId" to it) },
              )
            }
            "gym" -> {
              val id = JsonPrimitive(stable("gyms", r.id, n - "exerciseIds" - "equipmentIds"))
              replaceLinks(
                  "gym_exercises",
                  "gymId",
                  id,
                  n.getValue("exerciseIds").jsonArray.map {
                    mapOf("exerciseId" to localId("exercises", it))
                  },
              )
              replaceLinks(
                  "gym_equipment",
                  "gymId",
                  id,
                  n.getValue("equipmentIds").jsonArray.map { mapOf("equipmentId" to it) },
              )
            }
            "routine" -> {
              val id = JsonPrimitive(stable("routines", r.id, n - "exercises" - "gymIds"))
              replaceLinks(
                  "routine_gyms",
                  "routineId",
                  id,
                  n.getValue("gymIds").jsonArray.map { mapOf("gymId" to localId("gyms", it)) },
              )
              replaceLinks(
                  "routine_exercises",
                  "routineId",
                  id,
                  n.getValue("exercises").jsonArray.map { item ->
                    val e = item.jsonObject
                    (e - "plannedSets") +
                        mapOf(
                            "exerciseId" to localId("exercises", e.getValue("exerciseId")),
                            "plannedSetsJson" to
                                JsonPrimitive(e.getValue("plannedSets").toString()),
                        )
                  },
              )
            }
            "workout" -> {
              val id = JsonPrimitive(r.id)
              val body =
                  (n - "exercises" - "gymIds") +
                      mapOf(
                          "id" to id,
                          "routineId" to
                              (n["routineId"]
                                  ?.takeUnless { it == JsonNull }
                                  ?.let { localId("routines", it) } ?: JsonNull),
                          "uploadStatus" to JsonPrimitive("UPLOADED"),
                          "uploadError" to JsonNull,
                      )
              if (rows("workouts", "WHERE id=?", arrayOf(r.id)).isEmpty()) insert("workouts", body)
              else db.update("workouts", 0, values(body), "id=?", arrayOf(r.id))
              replaceLinks(
                  "workout_gyms",
                  "workoutId",
                  id,
                  n.getValue("gymIds").jsonArray.map { mapOf("gymId" to localId("gyms", it)) },
              )
              val sections = n.getValue("exercises").jsonArray.map { it.jsonObject }
              val wanted = sections.map { it.s("sectionId") }.toSet()
              rows("workout_exercises", "WHERE workoutId=?", arrayOf(r.id))
                  .filter { it.s("sectionId") !in wanted }
                  .forEach { db.delete("workout_exercises", "id=?", arrayOf(it.s("id"))) }
              sections.forEach { e ->
                val fields =
                    (e - "sets") +
                        mapOf(
                            "workoutId" to id,
                            "exerciseId" to localId("exercises", e.getValue("exerciseId")),
                        )
                val old =
                    rows("workout_exercises", "WHERE sectionId=?", arrayOf(e.s("sectionId")))
                        .firstOrNull()
                val section =
                    if (old == null) insert("workout_exercises", fields)
                    else
                        old.s("id").toLong().also {
                          db.update("workout_exercises", 0, values(fields), "id=?", arrayOf(it))
                        }
                // Never pull into a running local workout; the coordinator enforces this before
                // apply.
                replaceLinks(
                    "workout_sets",
                    "workoutExerciseId",
                    JsonPrimitive(section),
                    e.getValue("sets").jsonArray.map { it.jsonObject },
                )
              }
            }
            "measurement" -> {
              val body =
                  n +
                      mapOf(
                          "id" to JsonPrimitive(r.id),
                          "uploadStatus" to JsonPrimitive("UPLOADED"),
                          "uploadError" to JsonNull,
                      )
              if (rows("body_measurements", "WHERE id=?", arrayOf(r.id)).isEmpty())
                  insert("body_measurements", body)
              else db.update("body_measurements", 0, values(body), "id=?", arrayOf(r.id))
            }
            "schedule" -> {
              val body = n + mapOf("routineId" to localId("routines", n.getValue("routineId")))
              val old =
                  rows(
                          "scheduled_workouts",
                          "WHERE calendarEventId=?",
                          arrayOf(n.s("calendarEventId")),
                      )
                      .firstOrNull()
              if (old == null) insert("scheduled_workouts", body)
              else db.update("scheduled_workouts", 0, values(body), "id=?", arrayOf(old.s("id")))
            }
          }
        }
    deletes
        .sortedByDescending { order.indexOf(it.kind) }
        .forEach { r ->
          val table =
              when (r.kind) {
                "exercise" -> "exercises"
                "gym" -> "gyms"
                "routine" -> "routines"
                "workout" -> "workouts"
                "measurement" -> "body_measurements"
                else -> "scheduled_workouts"
              }
          if (r.kind == "schedule") {
            rows(table)
                .filter {
                  UUID.nameUUIDFromBytes(
                          "ValerochkaGym.schedule:${it.s("calendarEventId")}".toByteArray()
                      )
                      .toString() == r.id
                }
                .forEach { db.delete(table, "id=?", arrayOf(it.s("id"))) }
          } else
              db.delete(
                  table,
                  if (r.kind in setOf("workout", "measurement")) "id=?" else "syncId=?",
                  arrayOf(r.id),
              )
        }
  }
}
