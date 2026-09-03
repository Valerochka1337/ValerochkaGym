package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID
import java.util.Locale

/** A user named execution variant owned by one catalogue exercise. */
@Entity(
    tableName = "exercise_variants",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["syncId"], unique = true),
        // This unique parent key deliberately backs RoutineExercise's composite ownership FK.
        Index(value = ["exerciseId", "syncId"], unique = true),
        Index(value = ["exerciseId", "normalizedName"], unique = true),
        Index(value = ["exerciseId", "isArchived"]),
    ],
)
data class ExerciseVariantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncId: String = UUID.randomUUID().toString(),
    val exerciseId: Long,
    val name: String,
    val normalizedName: String = normalizedVariantName(name),
    val isArchived: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    init {
        require(name == name.trim() && name.isNotBlank()) { "Variant name must be nonblank and trimmed" }
        require(normalizedName == normalizedVariantName(name)) { "Variant normalized name must match name" }
    }
}

fun normalizedVariantName(name: String): String = name.trim().lowercase(Locale.ROOT)

fun ExerciseVariantEntity.withNextUpdatedAt(now: Long = System.currentTimeMillis()): ExerciseVariantEntity =
    copy(updatedAt = maxOf(now, updatedAt + 1))
