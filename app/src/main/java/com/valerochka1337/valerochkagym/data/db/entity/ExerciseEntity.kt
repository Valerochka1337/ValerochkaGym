package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.nio.charset.StandardCharsets
import java.util.UUID

@Entity(
    tableName = "exercises",
    indices = [Index(value = ["syncId"], unique = true)],
)
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val muscleGroup: MuscleGroup,
    val type: ExerciseType,
    val isCustom: Boolean = false,
    val syncId: String = UUID.randomUUID().toString(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Stable identity shared by the same built-in catalogue entry on every installation. */
fun builtInExerciseSyncId(name: String): String = deterministicExerciseSyncId("builtin:$name")

/**
 * One-time identity for a custom exercise that predates cloud IDs. The old local ID keeps duplicate
 * names distinct while making the v7 → v8 migration deterministic and therefore testable.
 */
fun migratedCustomExerciseSyncId(id: Long, name: String): String =
    deterministicExerciseSyncId("migrated-custom:$id:$name")

/** Returns a strictly newer exercise snapshot version even for saves in the same millisecond. */
fun ExerciseEntity.withNextUpdatedAt(now: Long = System.currentTimeMillis()): ExerciseEntity =
    copy(updatedAt = maxOf(now, updatedAt + 1))

private fun deterministicExerciseSyncId(identity: String): String =
    UUID.nameUUIDFromBytes("ValerochkaGym.exercise:$identity".toByteArray(StandardCharsets.UTF_8)).toString()
