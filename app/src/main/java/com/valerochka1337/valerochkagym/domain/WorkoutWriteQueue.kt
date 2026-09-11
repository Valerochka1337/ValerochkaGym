package com.valerochka1337.valerochkagym.domain

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide boundary for every active-workout write, including coach, screen and notification.
 */
@Singleton
class WorkoutWriteQueue @Inject constructor() {
  private val mutex = Mutex()

  suspend fun <T> write(block: suspend () -> T): T = mutex.withLock { block() }
}
