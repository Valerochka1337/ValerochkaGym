package com.valerochka1337.valerochkagym.data.calendar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.dao.CalendarEventAccountLinkDao
import com.valerochka1337.valerochkagym.data.db.dao.CalendarPlanDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.ScheduledWorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.CalendarEventAccountLinkState
import com.valerochka1337.valerochkagym.data.db.entity.CalendarGoogleLinkEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarMigrationMetadataEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarMigrationPhase
import com.valerochka1337.valerochkagym.data.db.entity.CalendarMigrationStateEntity
import com.valerochka1337.valerochkagym.data.db.entity.CalendarPlanEntity
import com.valerochka1337.valerochkagym.data.schedule.WeeklySchedule
import com.valerochka1337.valerochkagym.di.WeeklyScheduleOperations
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Durable startup gate for the legacy Room source. It only preserves and quarantines external
 * metadata; CAL-01 never replays a Google operation.
 */
@Singleton
class CalendarLegacyMigration
@Inject
constructor(
    private val database: GymDatabase,
    private val calendarDao: CalendarPlanDao,
    private val scheduledDao: ScheduledWorkoutDao,
    private val eventAccountLinkDao: CalendarEventAccountLinkDao,
    private val routineDao: RoutineDao,
    private val settings: DataStore<Preferences>,
    @WeeklyScheduleOperations private val operations: DataStore<Preferences>,
    private val faults: CalendarMigrationFaults,
    private val clock: CalendarMigrationClock,
) : CalendarMigrationGate {
  private val migrationMutex = Mutex()

  override suspend fun ensureReady(): Boolean = migrationMutex.withLock { ensureReadyLocked() }

  private suspend fun ensureReadyLocked(): Boolean {
    // READY is terminal. The retained legacy tables can still change while the app is used,
    // but CAL-01 must never reopen a completed one-time capture because of that change.
    if (isReady()) return true

    val captured = readSource()
    var sourceMatchesCapture = true
    database.withTransaction {
      calendarDao.insertMigrationState(CalendarMigrationStateEntity())
      if (calendarDao.migrationState()?.phase == CalendarMigrationPhase.READY)
          return@withTransaction
      val existing = calendarDao.migrationMetadata()
      if (existing == null) {
        val zone = clock.zoneId()
        calendarDao.upsertMigrationMetadata(
            CalendarMigrationMetadataEntity(
                sourceFingerprint = captured.fingerprint,
                zoneId = zone.id,
                weeklyStartLocalDate = clock.localDate(zone).toString(),
                observedOwners =
                    captured.schedule.ownerEmail?.let { "[\"${it.trim().lowercase()}\"]" }
                        ?: "[null]",
            )
        )
      }
      val metadata = requireNotNull(calendarDao.migrationMetadata())
      if (metadata.sourceFingerprint != captured.fingerprint) {
        sourceMatchesCapture = false
        return@withTransaction
      }
      if (calendarDao.migrationState()?.phase == CalendarMigrationPhase.PENDING) {
        copyRoomSource(captured, metadata)
        calendarDao.setMigrationPhase(CalendarMigrationPhase.ROOM_COPIED)
      }
    }
    if (!sourceMatchesCapture) return false
    if (calendarDao.migrationState()?.phase == CalendarMigrationPhase.PENDING) return false
    faults.afterRoomCopied()

    if (calendarDao.migrationState()?.phase == CalendarMigrationPhase.ROOM_COPIED) {
      settings.edit { it[COMPLETION_MARKER] = "ROOM_COPIED" }
      database.withTransaction {
        calendarDao.setMigrationPhase(CalendarMigrationPhase.DATASTORE_MARKED)
      }
    }
    faults.afterDataStoreMarked()

    if (calendarDao.migrationState()?.phase == CalendarMigrationPhase.DATASTORE_MARKED) {
      val metadata = calendarDao.migrationMetadata() ?: return false
      if (readSource().fingerprint != metadata.sourceFingerprint) return false
      settings.edit { it[COMPLETION_MARKER] = "READY" }
      faults.afterReadyMarker()
      database.withTransaction { calendarDao.setMigrationPhase(CalendarMigrationPhase.READY) }
    }
    return isReady()
  }

  suspend fun isReady(): Boolean =
      calendarDao.migrationState()?.phase == CalendarMigrationPhase.READY

  private fun legacyScheduleId(eventId: String): String =
      UUID.nameUUIDFromBytes("ValerochkaGym.schedule:$eventId".toByteArray(UTF_8)).toString()

  private suspend fun copyRoomSource(
      source: LegacySource,
      metadata: CalendarMigrationMetadataEntity,
  ) {
    val zone = ZoneId.of(metadata.zoneId)
    source.rows.forEach { legacy ->
      val legacyScheduleId = legacyScheduleId(legacy.calendarEventId)
      val date = Instant.ofEpochMilli(legacy.dateTimeMillis).atZone(zone).toLocalDate()
      if (date in LocalDate.of(1970, 1, 1)..LocalDate.of(2100, 12, 31)) {
        calendarDao.insertPlan(
            CalendarPlanEntity(
                CalendarTimeResolver.planId(legacyScheduleId),
                legacy.routineId,
                legacy.dateTimeMillis,
                metadata.zoneId,
                legacyScheduleId,
            )
        )
      }
      // An old event has no authority to acquire the current Google account. Preserve only raw
      // metadata in a local quarantine record.
      calendarDao.upsertGoogleLink(
          CalendarGoogleLinkEntity(
              "legacy_schedule",
              legacyScheduleId,
              source.ownerFor(legacy.id),
              null,
              legacy.calendarEventId,
              "QUARANTINED",
          )
      )
    }
    source.schedule.rules.forEach { legacy ->
      val syncId =
          routineDao.getRoutineWithExercises(legacy.routineId)?.routine?.syncId ?: return@forEach
      val localTime = "%02d:%02d".format(legacy.hour, legacy.minute)
      val legacyKey =
          listOf(
                  source.schedule.ownerEmail?.trim()?.lowercase() ?: "∅",
                  syncId,
                  legacy.isoDay,
                  localTime,
              )
              .joinToString("\u001F")
      calendarDao.insertRule(
          com.valerochka1337.valerochkagym.data.db.entity.CalendarRuleEntity(
              CalendarTimeResolver.ruleId(
                  source.schedule.ownerEmail,
                  syncId,
                  legacy.isoDay,
                  localTime,
              ),
              legacy.routineId,
              legacy.isoDay,
              localTime,
              metadata.zoneId,
              metadata.weeklyStartLocalDate,
              legacyKey,
          )
      )
    }
    source.rawOperation?.let {
      calendarDao.upsertGoogleLink(
          CalendarGoogleLinkEntity(
              "legacy_weekly_journal",
              fingerprintBytes(it),
              null,
              null,
              null,
              "QUARANTINED",
              error = it,
          )
      )
    }
  }

  private suspend fun readSource(): LegacySource {
    val preferences = settings.data.first()
    val rawSchedule = preferences[WEEKLY_SCHEDULE]
    val rawOperation = operations.data.first()[PENDING_OPERATION]
    val rows = scheduledDao.all()
    val eventOwners =
        eventAccountLinkDao.all().associate { link ->
          link.scheduledWorkoutId to
              (link.ownerEmail.takeIf { link.state == CalendarEventAccountLinkState.OWNED })
        }
    val schedule = decodeSchedule(rawSchedule)
    return LegacySource(
        rows,
        eventOwners,
        schedule,
        rawSchedule,
        rawOperation,
        fingerprint(rows, eventOwners, rawSchedule, rawOperation),
    )
  }

  private fun fingerprint(
      rows: List<com.valerochka1337.valerochkagym.data.db.entity.ScheduledWorkoutEntity>,
      owners: Map<Long, String?>,
      rawSchedule: String?,
      rawOperation: String?,
  ): String =
      MessageDigest.getInstance("SHA-256")
          .digest(
              (rows.joinToString("|") {
                    "${it.id}:${it.routineId}:${it.dateTimeMillis}:${it.calendarEventId}:${owners[it.id]}"
                  } + "|$rawSchedule|$rawOperation")
                  .toByteArray(UTF_8)
          )
          .joinToString("") { "%02x".format(it) }

  private fun fingerprintBytes(raw: String): String =
      MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(UTF_8)).joinToString("") {
        "%02x".format(it)
      }

  private fun decodeSchedule(raw: String?): WeeklySchedule =
      raw?.let { runCatching { json.decodeFromString<WeeklySchedule>(it) }.getOrNull() }
          ?: WeeklySchedule()

  private companion object {
    val json = Json { ignoreUnknownKeys = true }
    val WEEKLY_SCHEDULE = stringPreferencesKey("weekly_schedule")
    val PENDING_OPERATION = stringPreferencesKey("pending_operation")
    val COMPLETION_MARKER = stringPreferencesKey("calendar_legacy_migration_ready")
  }

  private data class LegacySource(
      val rows: List<com.valerochka1337.valerochkagym.data.db.entity.ScheduledWorkoutEntity>,
      val eventOwners: Map<Long, String?>,
      val schedule: WeeklySchedule,
      val rawSchedule: String?,
      val rawOperation: String?,
      val fingerprint: String,
  ) {
    fun ownerFor(scheduledWorkoutId: Long): String? = eventOwners[scheduledWorkoutId]
  }
}

/** Captures wall-clock values exactly once, while allowing restart tests to control them. */
@Singleton
open class CalendarMigrationClock @Inject constructor() {
  open fun zoneId(): ZoneId = ZoneId.systemDefault()

  open fun localDate(zone: ZoneId): LocalDate = LocalDate.now(zone)
}

/** Testable interruption seams; production has no failures injected. */
@Singleton
open class CalendarMigrationFaults @Inject constructor() {
  open fun afterRoomCopied() = Unit

  open fun afterDataStoreMarked() = Unit

  open fun afterReadyMarker() = Unit
}
