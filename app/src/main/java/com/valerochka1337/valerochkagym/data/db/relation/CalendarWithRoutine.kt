package com.valerochka1337.valerochkagym.data.db.relation

import androidx.room.Embedded
import com.valerochka1337.valerochkagym.data.db.entity.CalendarPlanEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarRuleEntity

data class CalendarPlanWithRoutine(@Embedded val plan: CalendarPlanEntity, val routineName: String)

data class CalendarRuleWithRoutine(@Embedded val rule: CalendarRuleEntity, val routineName: String)
