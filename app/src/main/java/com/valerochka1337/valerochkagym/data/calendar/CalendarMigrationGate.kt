package com.valerochka1337.valerochkagym.data.calendar

import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.entity.CalendarMigrationPhase

/** Fail-closed boundary shared by sync, account and retired Google entry points. */
interface CalendarMigrationGate {
  suspend fun ensureReady(): Boolean
}

/** Only used by direct unit construction; it never authorizes production work. */
object PendingCalendarMigrationGate : CalendarMigrationGate {
  override suspend fun ensureReady(): Boolean = false
}

/** Safe fallback for direct construction: it permits only an already durable READY state. */
class PersistedCalendarMigrationGate(private val database: GymDatabase) : CalendarMigrationGate {
  override suspend fun ensureReady(): Boolean =
      database.calendarPlanDao().migrationState()?.phase == CalendarMigrationPhase.READY
}
