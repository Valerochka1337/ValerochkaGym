package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import java.util.UUID

/** Версионируемая запись app-owned листа `Exercises`. */
sealed interface ExerciseSheetRecord {
  val syncId: String
  val updatedAt: Long

  data class Snapshot(
      override val syncId: String,
      override val updatedAt: Long,
      val name: String,
      val muscleGroup: MuscleGroup,
      val type: ExerciseType,
      val isCustom: Boolean,
      val muscleLoads: Map<Muscle, Int>,
      /** Legacy CHEST was expanded during parsing and needs a human review before next edit. */
      val needsMuscleMapReview: Boolean = false,
  ) : ExerciseSheetRecord

  data class Tombstone(
      override val syncId: String,
      override val updatedAt: Long,
  ) : ExerciseSheetRecord
}

data class ParsedExerciseSheetRows(
    val records: List<ExerciseSheetRecord>,
    val skippedRows: Int,
)

/** Версионируемая запись app-owned листа `Gyms`; состав зала входит в тот же снимок. */
sealed interface GymSheetRecord {
  val syncId: String
  val updatedAt: Long

  data class Snapshot(
      override val syncId: String,
      override val updatedAt: Long,
      val name: String,
      val exerciseSyncIds: Set<String>,
  ) : GymSheetRecord

  data class Tombstone(
      override val syncId: String,
      override val updatedAt: Long,
  ) : GymSheetRecord
}

data class ParsedGymSheetRows(
    val records: List<GymSheetRecord>,
    val skippedRows: Int,
)

/** Версионируемый набор залов одной программы из app-owned листа `RoutineGyms`. */
sealed interface RoutineGymsSheetRecord {
  val routineSyncId: String
  val updatedAt: Long

  data class Snapshot(
      override val routineSyncId: String,
      override val updatedAt: Long,
      val gymSyncIds: Set<String>,
  ) : RoutineGymsSheetRecord

  data class Tombstone(
      override val routineSyncId: String,
      override val updatedAt: Long,
  ) : RoutineGymsSheetRecord
}

data class ParsedRoutineGymsSheetRows(
    val records: List<RoutineGymsSheetRecord>,
    val skippedRows: Int,
)

/** Принимает только полную UUID-форму, но нормализует регистр через [UUID.toString]. */
internal fun canonicalSheetUuidOrNull(raw: String): String? {
  val value = raw.trim()
  if (value.isEmpty()) return null
  val parsed = runCatching { UUID.fromString(value) }.getOrNull() ?: return null
  return parsed.toString().takeIf { it.equals(value, ignoreCase = true) }
}

internal fun requireCanonicalSheetUuid(raw: String, field: String): String =
    requireNotNull(canonicalSheetUuidOrNull(raw)) { "$field должен быть UUID" }

internal fun requireSheetVersion(updatedAt: Long) {
  require(updatedAt > 0) { "updatedAt должен быть положительным" }
}

internal fun String.toSheetLongOrNull(): Long? {
  val value = trim()
  value.toLongOrNull()?.let {
    return it
  }
  return value.replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite)?.let { number ->
    number.toLong().takeIf { number == it.toDouble() }
  }
}

internal fun String.toSheetIntOrNull(): Int? =
    toSheetLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

internal fun String.toSheetBooleanOrNull(): Boolean? =
    when (trim().lowercase()) {
      "true",
      "1" -> true
      "false",
      "0" -> false
      else -> null
    }

internal fun List<String>.sheetCell(index: Int): String = getOrNull(index)?.trim().orEmpty()

internal fun List<String>.isBlankSheetRow(): Boolean = all(String::isBlank)
