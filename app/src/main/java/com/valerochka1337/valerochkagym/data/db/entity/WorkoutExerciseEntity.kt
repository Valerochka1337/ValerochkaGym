package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "workout_exercises",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exerciseId"],
        ),
    ],
    indices = [Index("workoutId"), Index("exerciseId"), Index(value = ["sectionId"], unique = true)],
)
data class WorkoutExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: String,
    val exerciseId: Long,
    /** Immutable row identity: unlike exerciseId it never collapses duplicate sections. */
    val sectionId: String = UUID.randomUUID().toString(),
    /** Stable comparison identity. No live FK: archived/missing definitions must not erase history. */
    val variantSyncId: String? = null,
    /** Immutable display value coupled with variantSyncId; both null denotes no variant. */
    val variantNameSnapshot: String? = null,
    val position: Int,
) {
    init {
        require(sectionId.isNotBlank()) { "Workout section id must be nonblank" }
        require((variantSyncId == null) == (variantNameSnapshot == null)) {
            "Variant identity and snapshot must be both present or both absent"
        }
        require(variantNameSnapshot == null || variantNameSnapshot == variantNameSnapshot.trim()) {
            "Variant snapshot must be trimmed"
        }
    }
}
