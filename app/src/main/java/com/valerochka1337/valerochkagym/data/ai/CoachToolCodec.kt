package com.valerochka1337.valerochkagym.data.ai

import java.util.UUID
import kotlinx.serialization.json.*

/**
 * Wire intents contain portable identifiers only. The host resolves them within the pinned owner.
 */
sealed interface CoachToolRequest {
  data object State : CoachToolRequest

  data class Find(val query: String?, val equipmentIds: Set<String>?, val muscleIds: Set<String>?) :
      CoachToolRequest

  data class History(val exerciseId: String) : CoachToolRequest

  data class Submit(
      val baseRevision: Long,
      val operations: List<CoachChangeIntent>,
      val reason: String?,
  ) : CoachToolRequest
}

sealed interface CoachChangeIntent {
  data class AddExercise(val exerciseId: String) : CoachChangeIntent

  data class RemoveRemaining(val sectionId: String) : CoachChangeIntent

  data class Move(val sectionId: String, val position: Int) : CoachChangeIntent

  data class Swap(val first: String, val second: String) : CoachChangeIntent

  data class Reorder(val sectionIds: List<String>) : CoachChangeIntent

  data class Replace(
      val sectionId: String,
      val exerciseId: String,
      val remainingSetIds: List<String>,
      val weightKg: Double?,
  ) : CoachChangeIntent

  data class AddSet(val sectionId: String) : CoachChangeIntent

  data class DeleteSet(val setId: String) : CoachChangeIntent

  data class EditSet(val setId: String, val values: CoachSetValues, val recordResult: Boolean) :
      CoachChangeIntent

  data class Complete(val setId: String, val completed: Boolean) : CoachChangeIntent

  data class Rest(val action: CoachRestAction, val seconds: Int?, val startId: String?) :
      CoachChangeIntent

  data class AvailableTime(val minutes: Int) : CoachChangeIntent

  data class OccupiedEquipment(val ids: Set<String>) : CoachChangeIntent

  data class ExcludedExercises(val ids: Set<String>) : CoachChangeIntent

  data class Feelings(val setId: String, val feelings: Set<String>) : CoachChangeIntent

  data object Undo : CoachChangeIntent
}

enum class CoachRestAction {
  START,
  EXTEND,
  SKIP,
  FUTURE_DURATION,
}

/** Supplied fields distinguish an omitted value from an explicit request to clear it. */
data class CoachSetValues(
    val supplied: Set<String>,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val durationSec: Int? = null,
    val speedKmh: Double? = null,
    val inclinePct: Double? = null,
)

class CoachToolValidationException(message: String) : IllegalArgumentException(message)

/** Strict local parser remains authoritative even for providers that ignore JSON Schema. */
object CoachToolCodec {
  private val json = Json {
    isLenient = false
    ignoreUnknownKeys = false
  }
  private val valueFields = setOf("weight_kg", "reps", "duration_sec", "speed_kmh", "incline_pct")
  private val feelingValues = setOf("PAIN", "FATIGUE", "TECHNIQUE_BREAKDOWN", "INTERRUPTED")

  private fun invalid(): Nothing =
      throw CoachToolValidationException("Некорректные аргументы инструмента. Уточните запрос.")

  fun decode(call: AiApiToolCall): CoachToolRequest {
    if (call.type != "function" || call.function.arguments.length > 32_000) invalid()
    val obj =
        try {
          json.parseToJsonElement(call.function.arguments) as? JsonObject ?: invalid()
        } catch (_: Exception) {
          invalid()
        }
    return when (call.function.name) {
      "get_workout_state" -> {
        obj.keys(emptySet())
        CoachToolRequest.State
      }
      "find_exercises" -> {
        obj.keys(setOf("query", "equipment_ids", "muscle_ids"))
        CoachToolRequest.Find(
            obj.optionalText("query", 200),
            obj.optionalStrings("equipment_ids"),
            obj.optionalStrings("muscle_ids"),
        )
      }
      "get_exercise_history" -> {
        obj.keys(setOf("exercise_id"))
        CoachToolRequest.History(obj.uuid("exercise_id"))
      }
      "submit_workout_changes" -> {
        obj.keys(setOf("base_revision", "operations", "reason"))
        val revision = obj.integer("base_revision", Long.MAX_VALUE)
        val ops = obj["operations"] as? JsonArray ?: invalid()
        if (ops.size !in 1..32) invalid()
        CoachToolRequest.Submit(
            revision,
            ops.map { operation(it as? JsonObject ?: invalid()) },
            obj.optionalText("reason", 1200),
        )
      }
      else -> invalid()
    }
  }

  private fun operation(obj: JsonObject): CoachChangeIntent {
    val action = obj.text("action")
    fun keys(vararg names: String) = obj.keys(names.toSet() + "action")
    return when (action) {
      "add_exercise" -> {
        keys("exercise_id")
        CoachChangeIntent.AddExercise(obj.uuid("exercise_id"))
      }
      "remove_remaining" -> {
        keys("section_id")
        CoachChangeIntent.RemoveRemaining(obj.uuid("section_id"))
      }
      "move_exercise" -> {
        keys("section_id", "position")
        CoachChangeIntent.Move(obj.uuid("section_id"), obj.integer("position", 1000).toInt())
      }
      "swap_exercises" -> {
        keys("first_section_id", "second_section_id")
        CoachChangeIntent.Swap(obj.uuid("first_section_id"), obj.uuid("second_section_id"))
      }
      "reorder_exercises" -> {
        keys("section_ids")
        CoachChangeIntent.Reorder(obj.uuids("section_ids", false))
      }
      "replace_remaining" -> {
        keys("section_id", "exercise_id", "remaining_set_ids", "weight_kg")
        CoachChangeIntent.Replace(
            obj.uuid("section_id"),
            obj.uuid("exercise_id"),
            obj.uuids("remaining_set_ids", false),
            obj.optionalNumber("weight_kg"),
        )
      }
      "add_set" -> {
        keys("section_id")
        CoachChangeIntent.AddSet(obj.uuid("section_id"))
      }
      "delete_set" -> {
        keys("set_id")
        CoachChangeIntent.DeleteSet(obj.uuid("set_id"))
      }
      "edit_set",
      "record_result" -> {
        keys("set_id", "values")
        val values = obj["values"] as? JsonObject ?: invalid()
        values.keys(valueFields)
        if (values.isEmpty()) invalid()
        CoachChangeIntent.EditSet(
            obj.uuid("set_id"),
            CoachSetValues(
                values.keys.toSet(),
                values.optionalNumber("weight_kg"),
                values.optionalInt("reps"),
                values.optionalInt("duration_sec"),
                values.optionalNumber("speed_kmh"),
                values.optionalNumber("incline_pct", -100.0),
            ),
            action == "record_result",
        )
      }
      "set_completed" -> {
        keys("set_id", "completed")
        val completed =
            (obj["completed"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: invalid()
        CoachChangeIntent.Complete(obj.uuid("set_id"), completed)
      }
      "start_rest",
      "extend_rest",
      "skip_rest",
      "future_rest_duration" -> {
        keys(
            *when (action) {
              "extend_rest" -> arrayOf("seconds", "rest_start_id")
              "skip_rest" -> arrayOf("rest_start_id")
              else -> arrayOf("seconds")
            }
        )
        val seconds =
            if (action == "skip_rest") null
            else obj.integer("seconds", 86_400).toInt().also { if (it <= 0) invalid() }
        val startId =
            if (action == "extend_rest" || action == "skip_rest")
                obj.text("rest_start_id").also { if (it.isBlank() || it.length > 200) invalid() }
            else null
        CoachChangeIntent.Rest(
            when (action) {
              "start_rest" -> CoachRestAction.START
              "extend_rest" -> CoachRestAction.EXTEND
              "skip_rest" -> CoachRestAction.SKIP
              else -> CoachRestAction.FUTURE_DURATION
            },
            seconds,
            startId,
        )
      }
      "available_time" -> {
        keys("minutes")
        CoachChangeIntent.AvailableTime(obj.integer("minutes", 1440).toInt())
      }
      "occupied_equipment" -> {
        keys("equipment_ids")
        CoachChangeIntent.OccupiedEquipment(obj.strings("equipment_ids"))
      }
      "excluded_exercises" -> {
        keys("exercise_ids")
        CoachChangeIntent.ExcludedExercises(obj.uuids("exercise_ids", true).toSet())
      }
      "report_feelings" -> {
        keys("set_id", "feelings")
        val feelings =
            obj.strings("feelings").also {
              if (it.any { value -> value !in feelingValues }) invalid()
            }
        CoachChangeIntent.Feelings(obj.uuid("set_id"), feelings)
      }
      "undo_last" -> {
        keys()
        CoachChangeIntent.Undo
      }
      else -> invalid()
    }
  }

  private fun JsonObject.keys(allowed: Set<String>) {
    if (keys.any { it !in allowed }) invalid()
  }

  private fun JsonObject.text(key: String): String =
      (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()

  private fun JsonObject.optionalText(key: String, max: Int): String? =
      if (key !in this) null else text(key).also { if (it.length > max) invalid() }

  private fun JsonObject.uuid(key: String): String = canonicalUuid(text(key))

  private fun canonicalUuid(raw: String): String =
      try {
        UUID.fromString(raw).toString().also { if (!it.equals(raw, ignoreCase = true)) invalid() }
      } catch (_: Exception) {
        invalid()
      }

  private fun JsonObject.integer(key: String, max: Long): Long =
      (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 0..max }
          ?: invalid()

  private fun JsonObject.optionalInt(key: String): Int? =
      if (key !in this || get(key) == JsonNull) null else integer(key, 1_000_000).toInt()

  private fun JsonObject.optionalNumber(key: String, min: Double = 0.0): Double? {
    if (key !in this || get(key) == JsonNull) return null
    return (get(key) as? JsonPrimitive)
        ?.takeIf { !it.isString }
        ?.doubleOrNull
        ?.takeIf { it.isFinite() && it in min..1_000_000.0 } ?: invalid()
  }

  private fun JsonObject.strings(key: String): Set<String> {
    val values = get(key) as? JsonArray ?: invalid()
    if (values.size > 100) invalid()
    val strings =
        values.map {
          (it as? JsonPrimitive)
              ?.takeIf { p -> p.isString }
              ?.content
              ?.takeIf { s -> s.isNotBlank() && s.length <= 120 } ?: invalid()
        }
    if (strings.distinct().size != strings.size) invalid()
    return strings.toSet()
  }

  private fun JsonObject.optionalStrings(key: String): Set<String>? =
      if (key !in this) null else strings(key)

  private fun JsonObject.uuids(key: String, allowEmpty: Boolean): List<String> =
      strings(key).map(::canonicalUuid).also {
        if ((!allowEmpty && it.isEmpty()) || it.distinct().size != it.size) invalid()
      }

  val tools: List<AiApiTool> by lazy {
    listOf(
        tool(
            "get_workout_state",
            "Полная активная тренировка, закреплённые ссылки, отдых, доступное оборудование, мышцы и свежий доступный пульс.",
            schema(emptyMap()),
        ),
        tool(
            "find_exercises",
            "Найти доступные упражнения по оборудованию, мышцам и названию; использовать только возвращённые UUID.",
            schema(
                mapOf(
                    "query" to stringSchema(),
                    "equipment_ids" to arraySchema(stringSchema()),
                    "muscle_ids" to arraySchema(stringSchema()),
                )
            ),
        ),
        tool(
            "get_exercise_history",
            "Последние три завершённые тренировки с упражнением. Неизвестные данные не заменяются нулями.",
            schema(mapOf("exercise_id" to uuidSchema()), "exercise_id"),
        ),
        tool(
            "submit_workout_changes",
            "Передать один пакет изменений. Приложение проверяет полномочия и сохраняет предложение или результат. Ожидание подтверждения завершает обращение.",
            schema(
                mapOf(
                    "base_revision" to numberSchema(true),
                    "operations" to
                        buildJsonObject {
                          put("type", "array")
                          put("minItems", 1)
                          put("maxItems", 32)
                          put(
                              "items",
                              buildJsonObject { put("anyOf", JsonArray(operationSchemas())) },
                          )
                        },
                    "reason" to stringSchema(),
                ),
                "base_revision",
                "operations",
            ),
        ),
    )
  }

  private fun operationSchemas(): List<JsonObject> {
    fun op(
        action: String,
        fields: Map<String, JsonElement> = emptyMap(),
        optional: Set<String> = emptySet(),
    ) =
        schema(
            mapOf(
                "action" to
                    buildJsonObject {
                      put("type", "string")
                      put("enum", JsonArray(listOf(JsonPrimitive(action))))
                    }
            ) + fields,
            *(listOf("action") + fields.keys.filter { it !in optional }).toTypedArray(),
        )
    val id = uuidSchema()
    val setValues =
        schema(
            valueFields.associateWith { field ->
              buildJsonObject {
                put(
                    "type",
                    JsonArray(
                        listOf(
                            JsonPrimitive(
                                if (field in setOf("reps", "duration_sec")) "integer" else "number"
                            ),
                            JsonPrimitive("null"),
                        )
                    ),
                )
                put("minimum", if (field == "incline_pct") -100 else 0)
                put("maximum", 1_000_000)
              }
            }
        )
    return listOf(
        op("add_exercise", mapOf("exercise_id" to id)),
        op("remove_remaining", mapOf("section_id" to id)),
        op("move_exercise", mapOf("section_id" to id, "position" to numberSchema(true))),
        op("swap_exercises", mapOf("first_section_id" to id, "second_section_id" to id)),
        op("reorder_exercises", mapOf("section_ids" to arraySchema(id))),
        op(
            "replace_remaining",
            mapOf(
                "section_id" to id,
                "exercise_id" to id,
                "remaining_set_ids" to arraySchema(id),
                "weight_kg" to numberSchema(false),
            ),
            setOf("weight_kg"),
        ),
        op("add_set", mapOf("section_id" to id)),
        op("delete_set", mapOf("set_id" to id)),
        op("edit_set", mapOf("set_id" to id, "values" to setValues)),
        op("record_result", mapOf("set_id" to id, "values" to setValues)),
        op(
            "set_completed",
            mapOf("set_id" to id, "completed" to buildJsonObject { put("type", "boolean") }),
        ),
        op("start_rest", mapOf("seconds" to numberSchema(true))),
        op(
            "extend_rest",
            mapOf("seconds" to numberSchema(true), "rest_start_id" to stringSchema()),
        ),
        op("skip_rest", mapOf("rest_start_id" to stringSchema())),
        op("future_rest_duration", mapOf("seconds" to numberSchema(true))),
        op("available_time", mapOf("minutes" to numberSchema(true))),
        op("occupied_equipment", mapOf("equipment_ids" to arraySchema(stringSchema()))),
        op("excluded_exercises", mapOf("exercise_ids" to arraySchema(id))),
        op(
            "report_feelings",
            mapOf(
                "set_id" to id,
                "feelings" to
                    arraySchema(
                        buildJsonObject {
                          put("type", "string")
                          put("enum", JsonArray(feelingValues.map(::JsonPrimitive)))
                        }
                    ),
            ),
        ),
        op("undo_last"),
    )
  }

  private fun tool(name: String, description: String, parameters: JsonObject) =
      AiApiTool(function = AiApiToolFunction(name, description, parameters))

  private fun schema(fields: Map<String, JsonElement>, vararg required: String) = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(fields))
    put("additionalProperties", false)
    put("required", JsonArray(required.map(::JsonPrimitive)))
  }

  private fun stringSchema() = buildJsonObject { put("type", "string") }

  private fun uuidSchema() = buildJsonObject {
    put("type", "string")
    put("format", "uuid")
  }

  private fun numberSchema(integer: Boolean) = buildJsonObject {
    put("type", if (integer) "integer" else "number")
    put("minimum", 0)
  }

  private fun arraySchema(items: JsonElement) = buildJsonObject {
    put("type", "array")
    put("items", items)
    put("maxItems", 100)
    put("uniqueItems", true)
  }
}
