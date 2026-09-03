package com.valerochka1337.valerochkagym.data.health

import android.net.Uri
import com.valerochka1337.valerochkagym.data.db.entity.MeasurementDocumentEntity
import com.valerochka1337.valerochkagym.data.measurements.MeasurementDocumentInput
import com.valerochka1337.valerochkagym.data.measurements.MeasurementDocumentRepository
import com.valerochka1337.valerochkagym.data.measurements.MeasurementDocumentStoreResult
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.InputStream
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HealthDocumentRecoveryCoordinatorTest {
    @Test
    fun `application start and first use perform one idempotent recovery`() = runTest {
        val documents = FakeDocuments()
        val measurements = FakeMeasurementDocuments()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val coordinator = HealthDocumentRecoveryCoordinator(documents, scope, measurements)

        coordinator.start()
        scope.advanceUntilIdle()
        coordinator.recoverOnce()
        coordinator.start()
        scope.advanceUntilIdle()

        assertEquals(1, documents.recoveryCalls)
        assertEquals(1, measurements.recoveryCalls)
        assertEquals(1, measurements.cameraRecoveryCalls)
    }

    private class FakeDocuments : HealthDocumentRepository {
        var recoveryCalls = 0
        override suspend fun store(input: HealthDocumentInput, source: InputStream) =
            HealthDocumentStoreResult.Failure("unused")
        override suspend fun storeUri(input: HealthDocumentInput, uri: Uri) =
            HealthDocumentStoreResult.Failure("unused")
        override suspend fun hasReadyDocument(documentId: String) = false
        override suspend fun delete(documentId: String) = false
        override suspend fun recoverInterruptedCopies() { recoveryCalls++ }
    }

    private class FakeMeasurementDocuments : MeasurementDocumentRepository {
        var recoveryCalls = 0
        var cameraRecoveryCalls = 0
        override suspend fun store(input: MeasurementDocumentInput, source: InputStream) =
            MeasurementDocumentStoreResult.Failure("unused")
        override suspend fun storeUri(input: MeasurementDocumentInput, uri: Uri) =
            MeasurementDocumentStoreResult.Failure("unused")
        override fun observeReady(measurementId: String): Flow<List<MeasurementDocumentEntity>> = emptyFlow()
        override suspend fun readyForMeasurement(measurementId: String) = emptyList<MeasurementDocumentEntity>()
        override suspend fun resolveReadyFile(documentId: String): File? = null
        override suspend fun delete(documentId: String) = false
        override suspend fun deleteAll(measurementId: String) = 0
        override suspend fun cleanupUnreferenced(hashes: Set<String>) = Unit
        override suspend fun recoverInterruptedCopies() { recoveryCalls++ }
        override suspend fun recoverCameraImports() { cameraRecoveryCalls++ }
    }
}
