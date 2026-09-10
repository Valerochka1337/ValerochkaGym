package com.valerochka1337.valerochkagym.data.calendar

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.dao.CalendarPlanDao
import com.valerochka1337.valerochkagym.data.db.entity.CalendarPlanEntity
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps an old-client schedule projection visible while turning its upsert into a local plan. */
@Singleton
class CalendarLegacyBridge
@Inject
constructor(private val database: GymDatabase, private val dao: CalendarPlanDao) {
  suspend fun upsertLegacySchedule(
      eventId: String,
      routineId: Long,
      startsAtMillis: Long,
      zoneId: String,
  ) =
      database.withTransaction {
        val legacyId =
            UUID.nameUUIDFromBytes("ValerochkaGym.schedule:$eventId".toByteArray(UTF_8)).toString()
        dao.insertPlan(
            CalendarPlanEntity(
                CalendarTimeResolver.planId(legacyId),
                routineId,
                startsAtMillis,
                zoneId,
                legacyId,
            )
        )
      }

  suspend fun deleteLegacySchedule(eventId: String) =
      database.withTransaction {
        val legacyId =
            UUID.nameUUIDFromBytes("ValerochkaGym.schedule:$eventId".toByteArray(UTF_8)).toString()
        dao.deletePlan(CalendarTimeResolver.planId(legacyId))
      }
}
