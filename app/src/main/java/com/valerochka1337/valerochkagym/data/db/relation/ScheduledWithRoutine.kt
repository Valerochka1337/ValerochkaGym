package com.valerochka1337.valerochkagym.data.db.relation

import androidx.room.Embedded
import com.valerochka1337.valerochkagym.data.db.entity.ScheduledWorkoutEntity

data class ScheduledWithRoutine(
    @Embedded val scheduled: ScheduledWorkoutEntity,
    val routineName: String,
)
