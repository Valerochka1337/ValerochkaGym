package com.valerochka1337.valerochkagym.data.db.relation

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.GymEntity
import com.valerochka1337.valerochkagym.data.db.entity.GymExerciseEntity

data class GymWithExercises(
    @Embedded val gym: GymEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = GymExerciseEntity::class,
            parentColumn = "gymId",
            entityColumn = "exerciseId",
        ),
    )
    val exercises: List<ExerciseEntity> = emptyList(),
)
