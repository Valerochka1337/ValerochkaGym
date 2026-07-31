package com.valerochka1337.valerochkagym.data.db.relation

import androidx.room.Embedded
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity

data class RoutineWithCount(
    @Embedded val routine: RoutineEntity,
    val exerciseCount: Int,
)
