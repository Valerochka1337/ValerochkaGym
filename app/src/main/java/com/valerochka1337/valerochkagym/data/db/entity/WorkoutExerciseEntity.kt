package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "workout_exercises",
    foreignKeys =
        [
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
    indices =
        [Index("workoutId"), Index("exerciseId"), Index(value = ["sectionId"], unique = true)],
)
data class WorkoutExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: String,
    val exerciseId: Long,
    /** Immutable row identity: duplicate base-exercise sections never collapse. */
    val sectionId: String = UUID.randomUUID().toString(),
    val position: Int,
) {
  init {
    require(sectionId.isNotBlank()) { "Workout section id must be nonblank" }
  }
}
