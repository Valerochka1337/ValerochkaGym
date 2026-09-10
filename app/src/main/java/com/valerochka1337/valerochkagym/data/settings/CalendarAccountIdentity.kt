package com.valerochka1337.valerochkagym.data.settings

import java.util.Locale
import kotlinx.coroutines.flow.Flow

/** Local Calendar identity boundary. Preferred is a picker hint; connected is a verified grant. */
interface CalendarAccountIdentity {
  val preferredCalendarEmail: Flow<String?>
  val connectedCalendarEmail: Flow<String?>

  suspend fun setPreferredCalendarEmail(email: String)

  suspend fun setConnectedCalendarEmail(email: String)

  /**
   * Commits a verified connection only while [canCommit] remains true at the storage write
   * boundary. Implementations backed by a transaction must invoke [canCommit] inside it.
   */
  suspend fun commitConnectedCalendarEmail(email: String, canCommit: () -> Boolean): Boolean

  /** Clears only the connection that the caller observed, so stale disconnects cannot win. */
  suspend fun clearConnectedCalendarEmail(expectedEmail: String): Boolean
}

fun normalizeCalendarEmail(value: String?): String? =
    value?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)
