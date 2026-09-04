package com.valerochka1337.valerochkagym.data.health

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one process-wide writer for private health/InBody originals and their camera markers.
 *
 * Room rows and their no-backup bytes cannot be made transactional together.  Keeping every
 * create, recovery and clear operation behind this gate makes the compensating cleanup ordered:
 * Clear Data cannot finish while a stale callback is publishing an original, and a publisher
 * cannot observe a partially cleared directory.
 */
@Singleton
class PrivateOriginalsLifecycleGate @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
