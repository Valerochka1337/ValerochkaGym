package com.valerochka1337.valerochkagym.data.db.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity

data class RoutineWithExercises(
    @Embedded val routine: RoutineEntity,
    @Relation(
        entity = RoutineExerciseEntity::class,
        parentColumn = "id",
        entityColumn = "routineId",
    )
    val exercises: List<RoutineExerciseWithExercise>,
)
