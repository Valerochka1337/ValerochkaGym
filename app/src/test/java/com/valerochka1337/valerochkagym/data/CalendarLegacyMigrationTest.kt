package com.valerochka1337.valerochkagym.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.data.calendar.CalendarLegacyMigration
import com.valerochka1337.valerochkagym.data.calendar.CalendarMigrationClock
import com.valerochka1337.valerochkagym.data.calendar.CalendarMigrationFaults
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.entity.CalendarEventAccountLinkEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarEventAccountLinkState
import com.valerochka1337.valerochkagym.data.db.entity.CalendarMigrationPhase
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.ScheduledWorkoutEntity
import com.valerochka1337.valerochkagym.data.schedule.DayRule
import com.valerochka1337.valerochkagym.data.schedule.WeeklySchedule
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CalendarLegacyMigrationTest {
  @Test
  fun `restart after room copy preserves captured source and never infers a Google owner`() =
      runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database =
            Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val settingsFile = File(context.cacheDir, "calendar-migration-settings-a.preferences_pb")
        val operationFile = File(context.cacheDir, "calendar-migration-operation-a.preferences_pb")
        settingsFile.delete()
        operationFile.delete()
        val settings = PreferenceDataStoreFactory.create(scope = backgroundScope) { settingsFile }
        val operations =
            PreferenceDataStoreFactory.create(scope = backgroundScope) { operationFile }
        try {
          database
              .routineDao()
              .upsertRoutine(RoutineEntity(id = 1, syncId = "routine-sync", name = "Ноги"))
          val scheduledId =
              database
                  .scheduledWorkoutDao()
                  .insert(
                      ScheduledWorkoutEntity(
                          routineId = 1,
                          dateTimeMillis = 1_700_000_000_000,
                          calendarEventId = "old-event",
                      )
                  )
          database
              .calendarEventAccountLinkDao()
              .insert(
                  CalendarEventAccountLinkEntity(
                      scheduledId,
                      null,
                      CalendarEventAccountLinkState.LEGACY_OWNER_UNKNOWN,
                  )
              )
          settings.edit {
            it[WEEKLY_SCHEDULE] =
                Json.encodeToString(WeeklySchedule(listOf(DayRule(1, 1, 8, 30)), ownerEmail = null))
          }
          operations.edit { it[PENDING_OPERATION] = "raw legacy Google operation" }

          val interrupted =
              migration(
                  database,
                  settings,
                  operations,
                  object : CalendarMigrationFaults() {
                    override fun afterRoomCopied(): Unit = error("simulated process death")
                  },
              )
          runCatching { interrupted.ensureReady() }
          assertEquals(
              CalendarMigrationPhase.ROOM_COPIED,
              database.calendarPlanDao().migrationState()?.phase,
          )
          assertEquals("[null]", database.calendarPlanDao().migrationMetadata()?.observedOwners)
          assertEquals(
              CalendarEventAccountLinkState.LEGACY_OWNER_UNKNOWN,
              database.calendarEventAccountLinkDao().getByScheduledId(scheduledId)?.state,
          )

          assertTrue(migration(database, settings, operations).ensureReady())
          assertEquals(
              CalendarMigrationPhase.READY,
              database.calendarPlanDao().migrationState()?.phase,
          )
          assertEquals(1, database.calendarPlanDao().planCount())
          assertEquals(1, database.calendarPlanDao().ruleCount())
          database.openHelper.writableDatabase
              .query(
                  "SELECT ownerEmail,status FROM calendar_google_links WHERE objectKind='legacy_schedule'"
              )
              .use {
                assertTrue(it.moveToFirst())
                assertNull(it.getString(0))
                assertEquals("QUARANTINED", it.getString(1))
              }
        } finally {
          database.close()
          settingsFile.delete()
          operationFile.delete()
        }
      }

  @Test
  fun `migration retains a known event owner only in its local quarantine`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val database =
        Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    val settingsFile = File(context.cacheDir, "calendar-migration-known.preferences_pb")
    val operationFile = File(context.cacheDir, "calendar-migration-known-operation.preferences_pb")
    settingsFile.delete()
    operationFile.delete()
    val settings = PreferenceDataStoreFactory.create(scope = backgroundScope) { settingsFile }
    val operations = PreferenceDataStoreFactory.create(scope = backgroundScope) { operationFile }
    try {
      database
          .routineDao()
          .upsertRoutine(RoutineEntity(id = 1, syncId = "routine-sync", name = "Ноги"))
      val scheduledId =
          database
              .scheduledWorkoutDao()
              .insert(
                  ScheduledWorkoutEntity(
                      routineId = 1,
                      dateTimeMillis = 1_700_000_000_000,
                      calendarEventId = "owned-event",
                  )
              )
      database
          .calendarEventAccountLinkDao()
          .insert(
              CalendarEventAccountLinkEntity(
                  scheduledId,
                  "owner@example.com",
                  CalendarEventAccountLinkState.OWNED,
              )
          )

      assertTrue(migration(database, settings, operations).ensureReady())
      database.openHelper.writableDatabase
          .query(
              "SELECT ownerEmail,status FROM calendar_google_links WHERE objectKind='legacy_schedule'"
          )
          .use {
            assertTrue(it.moveToFirst())
            assertEquals("owner@example.com", it.getString(0))
            assertEquals("QUARANTINED", it.getString(1))
          }
    } finally {
      database.close()
      settingsFile.delete()
      operationFile.delete()
    }
  }

  @Test
  fun `changed source after room copy keeps migration short of ready`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val database =
        Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    val settingsFile = File(context.cacheDir, "calendar-migration-settings-b.preferences_pb")
    val operationFile = File(context.cacheDir, "calendar-migration-operation-b.preferences_pb")
    settingsFile.delete()
    operationFile.delete()
    val settings = PreferenceDataStoreFactory.create(scope = backgroundScope) { settingsFile }
    val operations = PreferenceDataStoreFactory.create(scope = backgroundScope) { operationFile }
    try {
      database
          .routineDao()
          .upsertRoutine(RoutineEntity(id = 1, syncId = "routine-sync", name = "Ноги"))
      settings.edit {
        it[WEEKLY_SCHEDULE] = Json.encodeToString(WeeklySchedule(listOf(DayRule(1, 1, 8, 30))))
      }
      val interrupted =
          migration(
              database,
              settings,
              operations,
              object : CalendarMigrationFaults() {
                override fun afterRoomCopied(): Unit = error("stop")
              },
          )
      runCatching { interrupted.ensureReady() }
      settings.edit {
        it[WEEKLY_SCHEDULE] = Json.encodeToString(WeeklySchedule(listOf(DayRule(2, 1, 9, 0))))
      }

      assertFalse(migration(database, settings, operations).ensureReady())
      assertEquals(
          CalendarMigrationPhase.ROOM_COPIED,
          database.calendarPlanDao().migrationState()?.phase,
      )
    } finally {
      database.close()
      settingsFile.delete()
      operationFile.delete()
    }
  }

  @Test
  fun `restart after datastore marker finishes only after the source fingerprint still matches`() =
      runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database =
            Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val settingsFile = File(context.cacheDir, "calendar-migration-settings-c.preferences_pb")
        val operationFile = File(context.cacheDir, "calendar-migration-operation-c.preferences_pb")
        settingsFile.delete()
        operationFile.delete()
        val settings = PreferenceDataStoreFactory.create(scope = backgroundScope) { settingsFile }
        val operations =
            PreferenceDataStoreFactory.create(scope = backgroundScope) { operationFile }
        try {
          database
              .routineDao()
              .upsertRoutine(RoutineEntity(id = 1, syncId = "routine-sync", name = "Ноги"))
          settings.edit {
            it[WEEKLY_SCHEDULE] = Json.encodeToString(WeeklySchedule(listOf(DayRule(1, 1, 8, 30))))
          }
          val interrupted =
              migration(
                  database,
                  settings,
                  operations,
                  object : CalendarMigrationFaults() {
                    override fun afterDataStoreMarked(): Unit = error("stop after datastore marker")
                  },
              )
          runCatching { interrupted.ensureReady() }
          assertEquals(
              CalendarMigrationPhase.DATASTORE_MARKED,
              database.calendarPlanDao().migrationState()?.phase,
          )
          assertEquals("ROOM_COPIED", settings.data.first()[COMPLETION_MARKER])

          assertTrue(migration(database, settings, operations).ensureReady())
          assertEquals(
              CalendarMigrationPhase.READY,
              database.calendarPlanDao().migrationState()?.phase,
          )
          assertEquals("READY", settings.data.first()[COMPLETION_MARKER])
        } finally {
          database.close()
          settingsFile.delete()
          operationFile.delete()
        }
      }

  @Test
  fun `restart keeps its first captured zone and anchor when the device clock zone changes`() =
      runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database =
            Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val settingsFile = File(context.cacheDir, "calendar-migration-settings-zone.preferences_pb")
        val operationFile =
            File(context.cacheDir, "calendar-migration-operation-zone.preferences_pb")
        settingsFile.delete()
        operationFile.delete()
        val settings = PreferenceDataStoreFactory.create(scope = backgroundScope) { settingsFile }
        val operations =
            PreferenceDataStoreFactory.create(scope = backgroundScope) { operationFile }
        try {
          database
              .routineDao()
              .upsertRoutine(RoutineEntity(id = 1, syncId = "routine-sync", name = "Ноги"))
          settings.edit {
            it[WEEKLY_SCHEDULE] = Json.encodeToString(WeeklySchedule(listOf(DayRule(1, 1, 8, 30))))
          }
          val firstClock = FixedClock(ZoneId.of("Europe/Moscow"), LocalDate.of(2026, 9, 10))
          val interrupted =
              migration(
                  database,
                  settings,
                  operations,
                  faults =
                      object : CalendarMigrationFaults() {
                        override fun afterRoomCopied(): Unit = error("stop after capture")
                      },
                  clock = firstClock,
              )
          runCatching { interrupted.ensureReady() }

          assertTrue(
              migration(
                      database,
                      settings,
                      operations,
                      clock = FixedClock(ZoneId.of("UTC"), LocalDate.of(2040, 1, 1)),
                  )
                  .ensureReady()
          )
          val metadata = database.calendarPlanDao().migrationMetadata()
          assertEquals("Europe/Moscow", metadata?.zoneId)
          assertEquals("2026-09-10", metadata?.weeklyStartLocalDate)
        } finally {
          database.close()
          settingsFile.delete()
          operationFile.delete()
        }
      }

  @Test
  fun `reopened disk stores recover after ready marker fault without recapturing legacy source`() =
      runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "calendar-migration-ready-restart.db"
        val settingsFile =
            File(context.cacheDir, "calendar-migration-settings-ready.preferences_pb")
        val operationFile =
            File(context.cacheDir, "calendar-migration-operation-ready.preferences_pb")
        context.deleteDatabase(databaseName)
        settingsFile.delete()
        operationFile.delete()
        var firstScope: CoroutineScope? = null
        var secondScope: CoroutineScope? = null
        try {
          val firstDatabase = diskDatabase(context, databaseName)
          firstDatabase
              .routineDao()
              .upsertRoutine(RoutineEntity(id = 1, syncId = "routine-sync", name = "Ноги"))
          firstScope = testStoreScope()
          val firstSettings = PreferenceDataStoreFactory.create(scope = firstScope) { settingsFile }
          val firstOperations =
              PreferenceDataStoreFactory.create(scope = firstScope) { operationFile }
          firstSettings.edit {
            it[WEEKLY_SCHEDULE] = Json.encodeToString(WeeklySchedule(listOf(DayRule(1, 1, 8, 30))))
          }
          val interrupted =
              migration(
                  firstDatabase,
                  firstSettings,
                  firstOperations,
                  object : CalendarMigrationFaults() {
                    override fun afterReadyMarker(): Unit = error("stop after ready marker")
                  },
              )
          runCatching { interrupted.ensureReady() }
          assertEquals(
              CalendarMigrationPhase.DATASTORE_MARKED,
              firstDatabase.calendarPlanDao().migrationState()?.phase,
          )
          assertEquals("READY", firstSettings.data.first()[COMPLETION_MARKER])
          firstDatabase.close()
          firstScope.cancel()
          firstScope = null

          val reopenedDatabase = diskDatabase(context, databaseName)
          secondScope = testStoreScope()
          val reopenedSettings =
              PreferenceDataStoreFactory.create(scope = secondScope) { settingsFile }
          val reopenedOperations =
              PreferenceDataStoreFactory.create(scope = secondScope) { operationFile }
          assertTrue(
              migration(reopenedDatabase, reopenedSettings, reopenedOperations).ensureReady()
          )
          assertEquals(
              CalendarMigrationPhase.READY,
              reopenedDatabase.calendarPlanDao().migrationState()?.phase,
          )
          assertEquals(1, reopenedDatabase.calendarPlanDao().ruleCount())
          reopenedDatabase.close()
        } finally {
          firstScope?.cancel()
          secondScope?.cancel()
          context.deleteDatabase(databaseName)
          settingsFile.delete()
          operationFile.delete()
        }
      }

  private fun migration(
      database: GymDatabase,
      settings: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
      operations:
          androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
      faults: CalendarMigrationFaults = CalendarMigrationFaults(),
      clock: CalendarMigrationClock = CalendarMigrationClock(),
  ) =
      CalendarLegacyMigration(
          database,
          database.calendarPlanDao(),
          database.scheduledWorkoutDao(),
          database.calendarEventAccountLinkDao(),
          database.routineDao(),
          settings,
          operations,
          faults,
          clock,
      )

  private fun diskDatabase(context: Context, name: String): GymDatabase =
      Room.databaseBuilder(context, GymDatabase::class.java, name).allowMainThreadQueries().build()

  private fun testStoreScope(): CoroutineScope =
      CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

  private class FixedClock(private val zone: ZoneId, private val date: LocalDate) :
      CalendarMigrationClock() {
    override fun zoneId(): ZoneId = zone

    override fun localDate(zone: ZoneId): LocalDate = date
  }

  private companion object {
    val WEEKLY_SCHEDULE = stringPreferencesKey("weekly_schedule")
    val PENDING_OPERATION = stringPreferencesKey("pending_operation")
    val COMPLETION_MARKER = stringPreferencesKey("calendar_legacy_migration_ready")
  }
}
