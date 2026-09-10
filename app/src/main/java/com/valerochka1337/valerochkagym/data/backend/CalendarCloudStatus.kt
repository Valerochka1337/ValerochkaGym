package com.valerochka1337.valerochkagym.data.backend

import kotlinx.coroutines.flow.Flow

/** Cloud capability is informational: Room commits do not wait for server support. */
enum class CalendarCloudState {
  Pending,
  Unsupported,
  Available,
}

interface CalendarCloudStatus {
  val calendarCloudState: Flow<CalendarCloudState>
}
