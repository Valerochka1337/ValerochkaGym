package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A private annotation keyed by the portable exercise identity, including built-in exercises. */
@Entity(tableName = "exercise_personal_hints")
data class ExercisePersonalHintEntity(
    @PrimaryKey val exerciseSyncId: String,
    val text: String,
    val updatedAt: Long,
)
