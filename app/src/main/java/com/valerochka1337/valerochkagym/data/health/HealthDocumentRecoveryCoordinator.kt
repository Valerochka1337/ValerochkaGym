package com.valerochka1337.valerochkagym.data.health

import com.valerochka1337.valerochkagym.di.ApplicationScope
import com.valerochka1337.valerochkagym.data.measurements.MeasurementDocumentRepository
import com.valerochka1337.valerochkagym.data.measurements.NoOpMeasurementDocumentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Process-lifetime, idempotent recovery gate for private health originals. */
@Singleton
class HealthDocumentRecoveryCoordinator @Inject constructor(
    private val documents: HealthDocumentRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val measurementDocuments: MeasurementDocumentRepository = NoOpMeasurementDocumentRepository,
) {
    private val mutex = Mutex()
    private var recovered = false

    /** Starts on the application scope, so Application.onCreate never blocks the main thread. */
    fun start() {
        scope.launch { runCatching { recoverOnce() } }
    }

    /** Export and other first-use boundaries can await the same single recovery safely. */
    suspend fun recoverOnce() = mutex.withLock {
        if (recovered) return@withLock
        documents.recoverInterruptedCopies()
        measurementDocuments.recoverInterruptedCopies()
        measurementDocuments.recoverCameraImports()
        recovered = true
    }
}
