package com.valerochka1337.valerochkagym.data.ai

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CoachToolCodecTest {
  @Test
  fun `tool output retains portable fields metadata and null omission`() {
    val set = com.valerochka1337.valerochkagym.domain.SnapshotSet("set", 0, true, 50.0, 6, null,
        originalReps = 10, targetReps = 8, actualReps = 6)
    val snapshot = com.valerochka1337.valerochkagym.domain.WorkoutSnapshot("owner", "workout", 3,
        listOf(com.valerochka1337.valerochkagym.domain.SnapshotExercise("section", 1, "exercise", "Жим", sets = listOf(set))),
        rest = com.valerochka1337.valerochkagym.domain.SnapshotRest("rest", 90, 30, 0),
        pulse = com.valerochka1337.valerochkagym.domain.SnapshotPulse(120, 123L))
    val output = Json.parseToJsonElement(CoachToolCodec.snapshotJson(snapshot)).jsonObject
    assertEquals(JsonPrimitive("workout"), output["workout_id"])
    assertEquals(JsonPrimitive(3), output["revision"])
    assertFalse("occupied_equipment" in output)
    assertEquals(Json.parseToJsonElement("{\"bpm\":120,\"measured_at_millis\":123}"), output["pulse"])
    assertEquals(Json.parseToJsonElement("{\"start_id\":\"rest\",\"planned_seconds\":90,\"remaining_seconds\":30}"), output["rest"])
    val row = output.getValue("exercises").jsonArray.single().jsonObject.getValue("sets").jsonArray.single().jsonObject
    assertEquals(JsonPrimitive(10), row["original_reps"])
    assertEquals(JsonPrimitive(8), row["target_reps"])
    assertEquals(JsonPrimitive(6), row["actual_reps"])
    assertFalse("duration_sec" in row)
    assertFalse("account_id" in output)
    val found = CoachToolCodec.foundJson(listOf(com.valerochka1337.valerochkagym.domain.FoundCoachExercise("e", "Жим", setOf("b", "a"), emptySet())))
    assertEquals("""{"exercises":[{"exercise_id":"e","name":"Жим","muscles":["a","b"],"equipment":[]}]}""", found)
    val history = CoachToolCodec.historyJson(listOf(com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity(
        workoutExerciseId = 1, setIndex = 2, completedAt = 123L, reps = 6)))
    assertEquals("""{"history":[{"completed_at":123,"set_index":2,"reps":6}]}""", history)
  }

  @Test
  fun `registered tools expose closed object schemas`() {
    assertEquals(
        setOf(
            "get_workout_state",
            "find_exercises",
            "get_exercise_history",
            "submit_workout_changes",
        ),
        CoachToolCodec.tools.map { it.function.name }.toSet(),
    )
    CoachToolCodec.tools.forEach {
      val schema = it.function.parameters.jsonObject
      assertEquals(JsonPrimitive("object"), schema["type"])
      assertEquals(JsonPrimitive(false), schema["additionalProperties"])
    }
  }

  @Test
  fun `state read rejects injected permission or owner fields`() {
    listOf("permission", "confirmed", "account_id", "workout_id").forEach {
      rejected("get_workout_state", "{\"$it\":true}")
    }
  }

  @Test
  fun `tool requests contain portable exercise identity`() {
    val result =
        decode("get_exercise_history", "{\"exercise_id\":\"$EXERCISE\"}")
            as CoachToolRequest.History
    assertEquals(EXERCISE, result.exerciseId)
    rejected("get_exercise_history", "{\"exercise_id\":42}")
    rejected("get_exercise_history", "{\"exercise_id\":\"1-1-1-1-1\"}")
  }

  @Test
  fun `find combines explicit equipment and muscle filters`() {
    val request =
        decode(
            "find_exercises",
            """{"query":"жим","equipment_ids":["barbell"],"muscle_ids":["CHEST"]}""",
        )
            as CoachToolRequest.Find
    assertEquals("жим", request.query)
    assertEquals(setOf("barbell"), request.equipmentIds)
    assertEquals(setOf("CHEST"), request.muscleIds)
  }

  @Test
  fun `valid set edits preserve omitted versus cleared fields`() {
    val intent =
        submit(
                """{"action":"edit_set","set_id":"$SET","values":{"reps":6,"weight_kg":null,"incline_pct":-2.5}}"""
            )
            .operations
            .single() as CoachChangeIntent.EditSet
    assertEquals(SET, intent.setId)
    assertEquals(setOf("reps", "weight_kg", "incline_pct"), intent.values.supplied)
    assertEquals(6, intent.values.reps)
    assertNull(intent.values.weightKg)
    assertEquals(-2.5, intent.values.inclinePct!!, 0.0)
    assertFalse(intent.recordResult)
  }

  @Test
  fun `record result is distinct from a target edit and completion`() {
    val intent =
        submit("""{"action":"record_result","set_id":"$SET","values":{"reps":6}}""")
            .operations
            .single() as CoachChangeIntent.EditSet
    assertTrue(intent.recordResult)
    val completed =
        submit("""{"action":"set_completed","set_id":"$SET","completed":true}""")
            .operations
            .single() as CoachChangeIntent.Complete
    assertTrue(completed.completed)
  }

  @Test
  fun `model cannot smuggle authorization in a packet or operation`() {
    rejected(
        "submit_workout_changes",
        """{"base_revision":0,"confirmed":true,"operations":[{"action":"undo_last"}]}""",
    )
    rejectedSubmit("""{"action":"add_set","section_id":"$SECTION","permission":"user"}""")
    rejectedSubmit("""{"action":"edit_set","set_id":"$SET","values":{"reps":6,"approved":true}}""")
  }

  @Test
  fun `invalid numbers never become set updates`() {
    listOf("-1", "\"6\"", "6.5", "1000001", "true").forEach {
      rejectedSubmit("""{"action":"edit_set","set_id":"$SET","values":{"reps":$it}}""")
    }
    listOf("1e999", "-5", "\"NaN\"").forEach {
      rejectedSubmit("""{"action":"edit_set","set_id":"$SET","values":{"weight_kg":$it}}""")
    }
    rejectedSubmit("""{"action":"edit_set","set_id":"$SET","values":{}}""")
  }

  @Test
  fun `replacement binds exact source remainder without inventing weight`() {
    val intent =
        submit(
                """{"action":"replace_remaining","section_id":"$SECTION","exercise_id":"$EXERCISE","remaining_set_ids":["$SET"]}"""
            )
            .operations
            .single() as CoachChangeIntent.Replace
    assertNull(intent.weightKg)
    assertEquals(listOf(SET), intent.remainingSetIds)
    rejectedSubmit(
        """{"action":"replace_remaining","section_id":"$SECTION","exercise_id":"$EXERCISE","remaining_set_ids":["$SET","$SET"]}"""
    )
  }

  @Test
  fun `rest commands require identity only for an existing timer`() {
    val intent =
        submit("""{"action":"extend_rest","rest_start_id":"timer-a","seconds":30}""")
            .operations
            .single() as CoachChangeIntent.Rest
    assertEquals(CoachRestAction.EXTEND, intent.action)
    assertEquals("timer-a", intent.startId)
    assertEquals(30, intent.seconds)
    rejectedSubmit("""{"action":"extend_rest","seconds":30}""")
    rejectedSubmit("""{"action":"skip_rest","rest_start_id":"timer-a","seconds":30}""")
    rejectedSubmit("""{"action":"start_rest","seconds":-30}""")
  }

  @Test
  fun `feelings contain only reported supported categories`() {
    val intent =
        submit("""{"action":"report_feelings","set_id":"$SET","feelings":["PAIN"]}""")
            .operations
            .single() as CoachChangeIntent.Feelings
    assertEquals(setOf("PAIN"), intent.feelings)
    rejectedSubmit("""{"action":"report_feelings","set_id":"$SET","feelings":["DIAGNOSIS"]}""")
  }

  @Test
  fun `every approved operation has a typed decoding path`() {
    val entries =
        listOf(
            """{"action":"add_exercise","exercise_id":"$EXERCISE"}""",
            """{"action":"remove_remaining","section_id":"$SECTION"}""",
            """{"action":"move_exercise","section_id":"$SECTION","position":1}""",
            """{"action":"swap_exercises","first_section_id":"$SECTION","second_section_id":"$OTHER"}""",
            """{"action":"reorder_exercises","section_ids":["$OTHER","$SECTION"]}""",
            """{"action":"add_set","section_id":"$SECTION"}""",
            """{"action":"delete_set","set_id":"$SET"}""",
            """{"action":"start_rest","seconds":90}""",
            """{"action":"skip_rest","rest_start_id":"timer-a"}""",
            """{"action":"future_rest_duration","seconds":120}""",
            """{"action":"available_time","minutes":20}""",
            """{"action":"excluded_exercises","exercise_ids":["$EXERCISE"]}""",
            """{"action":"undo_last"}""",
        )
    assertEquals(entries.size, submit(entries.joinToString(",")).operations.size)
  }

  @Test
  fun `empty oversized and unknown operation packets are rejected`() {
    rejected("submit_workout_changes", """{"base_revision":0,"operations":[]}""")
    rejectedSubmit(List(33) { """{"action":"undo_last"}""" }.joinToString(","))
    rejectedSubmit("""{"action":"execute_sql","sql":"DELETE FROM workouts"}""")
    rejected(
        "submit_workout_changes",
        """{"base_revision":-1,"operations":[{"action":"undo_last"}]}""",
    )
  }

  @Test
  fun `retired occupied equipment action is rejected`() {
    rejectedSubmit("""{"action":"occupied_equipment","equipment_ids":["barbell"]}""")
  }

  @Test
  fun `malformed json and non function calls are rejected`() {
    rejected("get_workout_state", "[]")
    rejected("get_workout_state", "{bad}")
    try {
      CoachToolCodec.decode(
          AiApiToolCall("x", "computer", AiApiToolCallFunction("get_workout_state", "{}"))
      )
      fail("Expected rejection")
    } catch (_: CoachToolValidationException) {}
  }

  @Test
  fun `reorder accepts bounded operation explanation from model response`() {
    val result = submit(
        """{"action":"reorder_exercises","section_ids":["$OTHER","$SECTION"],"reason":"Перенести жим в Хаммере на место занятого жима лёжа"}"""
    )
    assertEquals(listOf(CoachChangeIntent.Reorder(listOf(OTHER, SECTION))), result.operations)
    rejectedSubmit("""{"action":"reorder_exercises","section_ids":["$OTHER","$SECTION"],"reason":42}""")
    rejectedSubmit("""{"action":"reorder_exercises","section_ids":["$OTHER","$SECTION"],"reason":"${"x".repeat(1201)}"}""")
    rejectedSubmit("""{"action":"reorder_exercises","section_ids":["$OTHER","$SECTION"],"approved":true}""")
  }

  private fun decode(name: String, arguments: String) =
      CoachToolCodec.decode(
          AiApiToolCall("test", function = AiApiToolCallFunction(name, arguments))
      )

  private fun submit(operations: String) =
      decode("submit_workout_changes", """{"base_revision":7,"operations":[$operations]}""")
          as CoachToolRequest.Submit

  private fun rejectedSubmit(operations: String) =
      rejected("submit_workout_changes", """{"base_revision":0,"operations":[$operations]}""")

  private fun rejected(name: String, arguments: String) {
    try {
      decode(name, arguments)
      fail("Expected invalid arguments")
    } catch (_: CoachToolValidationException) {}
  }

  companion object {
    private const val EXERCISE = "00000000-0000-4000-8000-000000000001"
    private const val SECTION = "00000000-0000-4000-8000-000000000002"
    private const val SET = "00000000-0000-4000-8000-000000000003"
    private const val OTHER = "00000000-0000-4000-8000-000000000004"
  }
}
