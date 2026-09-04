package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises
import kotlinx.serialization.json.Json

/**
 * Снимок одной пользовательской программы для листа `Routines`.
 *
 * Лист append-only: после каждой правки записывается весь новый снимок с тем же `routine_id` и
 * бо́льшим `updated_at`. Это сохраняет историю без опасного построчного update, а импорт выбирает
 * последнюю версию. Удаление представлено отдельной tombstone-строкой.
 */
object RoutineRowMapper {

  val HEADER_ROW: List<String> =
      listOf(
          "routine_id",
          "updated_at",
          "is_deleted",
          "routine_name",
          "routine_note",
          "exercise_position",
          "exercise_name",
          "muscle_group",
          "type",
          "rest_seconds",
          "planned_sets_json",
          "exercise_id",
          "variant_id",
      )

  /** Формат до gym-config: импорт по-прежнему принимает эти 11 колонок. */
  val LEGACY_HEADER_ROW: List<String> = HEADER_ROW.take(11)

  fun rows(routine: RoutineWithExercises): List<List<Any?>> {
    val base =
        listOf<Any?>(
            routine.routine.syncId,
            routine.routine.updatedAt,
            "false",
            routine.routine.name,
            routine.routine.note,
        )
    val exercises = routine.exercises.sortedBy { it.routineExercise.position }
    if (exercises.isEmpty()) {
      // Старые/переданные вручную программы без упражнений остаются восстанавливаемыми.
      return listOf(base + List(8) { "" })
    }
    return exercises.map { item ->
      base +
          listOf(
              item.routineExercise.position,
              item.exercise.name,
              item.exercise.muscleGroup.name,
              item.exercise.type.name,
              item.routineExercise.restSeconds,
              JSON.encodeToString(item.routineExercise.plannedSets),
              item.exercise.syncId,
              "",
          )
    }
  }

  fun deletion(syncId: String, updatedAt: Long): List<Any?> =
      listOf(
          syncId,
          updatedAt,
          "true",
          "",
          "",
          "",
          "",
          "",
          "",
          "",
          "",
          "",
          "",
      )

  private val JSON = Json
}
