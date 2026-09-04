package com.valerochka1337.valerochkagym.data.schedule

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.data.google.CalendarEventDto
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal enum class WeeklyScheduleOperationKind {
  REPLACE,
  CLEAR,
}

@Serializable
internal enum class WeeklyScheduleOperationPhase {
  CREATE_NEW,
  CLEANUP_NEW,
  DELETE_OLD,
}

@Serializable
internal data class PreparedCalendarEvent(
    val eventId: String,
    val rule: DayRule,
    val request: CalendarEventDto,
)

@Serializable
internal data class WeeklyScheduleOperation(
    val kind: WeeklyScheduleOperationKind,
    val phase: WeeklyScheduleOperationPhase,
    val accountEmail: String,
    val oldSchedule: WeeklySchedule,
    val targetSchedule: WeeklySchedule,
    val preparedEvents: List<PreparedCalendarEvent>,
    val pendingCreateIds: List<String>,
    val cleanupNewIds: List<String>,
    val pendingDeleteIds: List<String>,
    val failureMessage: String? = null,
)

internal sealed interface WeeklyScheduleOperationRead {
  data object Absent : WeeklyScheduleOperationRead

  data class Present(val operation: WeeklyScheduleOperation) : WeeklyScheduleOperationRead

  data class Retryable(val cause: IOException) : WeeklyScheduleOperationRead

  data class Unreadable(val cause: Throwable) : WeeklyScheduleOperationRead
}

internal class WeeklyScheduleOperationJournal(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {
  suspend fun read(): WeeklyScheduleOperationRead =
      try {
        val raw =
            dataStore.data.first()[PENDING_OPERATION] ?: return WeeklyScheduleOperationRead.Absent
        val operation = json.decodeFromString<WeeklyScheduleOperation>(raw)
        validateWeeklyScheduleOperation(operation)
        WeeklyScheduleOperationRead.Present(operation)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: IOException) {
        WeeklyScheduleOperationRead.Retryable(error)
      } catch (error: Exception) {
        WeeklyScheduleOperationRead.Unreadable(error)
      }

  suspend fun write(operation: WeeklyScheduleOperation) {
    validateWeeklyScheduleOperation(operation)
    val encoded = json.encodeToString(operation)
    dataStore.edit { it[PENDING_OPERATION] = encoded }
  }

  suspend fun clear() {
    dataStore.edit { it.remove(PENDING_OPERATION) }
  }

  private companion object {
    val PENDING_OPERATION = stringPreferencesKey("pending_operation")
  }
}

internal fun validateWeeklyScheduleOperation(operation: WeeklyScheduleOperation) {
  require(operation.accountEmail == normalizeEmail(operation.accountEmail))
  require(operation.accountEmail.isNotEmpty())
  require(operation.preparedEvents.map { it.eventId }.isDistinct())
  require(operation.pendingCreateIds.isDistinct())
  require(operation.cleanupNewIds.isDistinct())
  require(operation.pendingDeleteIds.isDistinct())

  val preparedIds = operation.preparedEvents.map { it.eventId }
  operation.preparedEvents.forEach { prepared ->
    require(EVENT_ID.matches(prepared.eventId))
    require(prepared.request.id == prepared.eventId)
    require(prepared.rule.calendarEventId == prepared.eventId)
  }
  require(operation.pendingCreateIds.all(preparedIds::contains))
  require(operation.cleanupNewIds.all(preparedIds::contains))
  require(operation.pendingDeleteIds.all(operation.oldSchedule.eventIds()::contains))

  when (operation.kind) {
    WeeklyScheduleOperationKind.REPLACE -> {
      require(operation.targetSchedule.rules.mapNotNull { it.calendarEventId } == preparedIds)
      require(normalizeEmail(operation.targetSchedule.ownerEmail) == operation.accountEmail)
    }
    WeeklyScheduleOperationKind.CLEAR -> {
      require(operation.preparedEvents.isEmpty())
      require(operation.pendingCreateIds.isEmpty())
      require(operation.cleanupNewIds.isEmpty())
      require(operation.targetSchedule.rules.isEmpty())
    }
  }
  if (operation.phase == WeeklyScheduleOperationPhase.DELETE_OLD) {
    require(operation.pendingCreateIds.isEmpty())
  }
}

internal fun WeeklySchedule.eventIds(): List<String> =
    rules.mapNotNull { it.calendarEventId }.distinct()

internal fun normalizeEmail(email: String?): String =
    email?.trim()?.lowercase(Locale.ROOT).orEmpty()

private fun <T> List<T>.isDistinct(): Boolean = size == distinct().size

private val EVENT_ID = Regex("[0-9a-f]{32}")
