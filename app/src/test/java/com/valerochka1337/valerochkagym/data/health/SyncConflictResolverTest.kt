package com.valerochka1337.valerochkagym.data.health

import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthObservationEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportSnapshotEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRestrictionEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRestrictionSnapshotEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncConflictEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity
import com.valerochka1337.valerochkagym.data.db.entity.MeasurementSnapshotEntity
import com.valerochka1337.valerochkagym.data.db.entity.MeasurementDocumentEntity
import com.valerochka1337.valerochkagym.data.measurements.MeasurementDocumentRepository
import com.valerochka1337.valerochkagym.data.measurements.MeasurementRepository
import com.valerochka1337.valerochkagym.data.measurements.NoOpMeasurementDocumentRepository
import com.valerochka1337.valerochkagym.worker.HealthSyncScheduler
import com.valerochka1337.valerochkagym.worker.MeasurementUploadScheduler
import com.valerochka1337.valerochkagym.data.settings.HealthSyncCategory as SettingsCategory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncConflictResolverTest : RoomDaoTest() {
    @Test
    fun `resolving report local and remote choices appends successor snapshots and exact work`() = runTest {
        resolveReport(ConflictChoice.LOCAL, "local Hb")
        db.close()
        createDatabase()
        resolveReport(ConflictChoice.REMOTE, "remote Hb")
    }

    @Test
    fun `resolving restriction local and remote choices appends successor snapshots and exact work`() = runTest {
        resolveRestriction(ConflictChoice.LOCAL, "Local restriction")
        db.close()
        createDatabase()
        resolveRestriction(ConflictChoice.REMOTE, "Remote restriction")
    }

    @Test
    fun `resolving measurement local and remote choices appends successor snapshots and exact work`() = runTest {
        resolveMeasurement(ConflictChoice.LOCAL, 70.0)
        db.close()
        createDatabase()
        resolveMeasurement(ConflictChoice.REMOTE, 80.0)
    }

    @Test
    fun `malformed selected payload leaves every durable state and marker untouched`() = runTest {
        val local = measurement("m", 70.0)
        val payload = MeasurementRepository.canonicalPayload(local)
        db.bodyMeasurementDao().insert(local)
        db.healthDao().insertMeasurementSnapshot(MeasurementSnapshotEntity("m", 1, 1, false, payload, MeasurementRepository.sha256(payload)))
        db.healthDao().upsertConflict(
            HealthSyncConflictEntity(HealthSyncCategory.MEASUREMENTS, "m", 1, payload, MeasurementRepository.sha256(payload), "not a payload", null, 2),
        )
        val scheduler = FakeScheduler(db)
        val measurementScheduler = FakeMeasurementScheduler()

        val result = SyncConflictResolver(db, scheduler, measurementUploadScheduler = measurementScheduler).resolve(
            HealthSyncCategory.MEASUREMENTS, "m", 1, ConflictChoice.REMOTE, now = 10,
        )

        assertEquals(ConflictResolutionResult.InvalidPayload, result)
        assertEquals(local, db.bodyMeasurementDao().getById("m"))
        assertEquals(listOf(1L), db.healthDao().measurementSnapshots("m").map { it.version })
        assertEquals(0, tableCount("health_sync_outbox"))
        assertTrue(scheduler.entries.isEmpty())
        assertTrue(db.healthDao().conflict(HealthSyncCategory.MEASUREMENTS, "m", 1) != null)
    }

    @Test
    fun `resolving a tombstone keeps it tombstoned and removes only the current projection`() = runTest {
        val report = HealthReportEntity("deleted", 1, 1, false, "CONFIRMED", "LAB", 1, "CBC")
        val observation = HealthObservationEntity("observation", "deleted", 1, 1, false, 1, "Hb", "NUMBER", "120")
        val localPayload = HealthSyncPayloadCodec.report(report, listOf(observation))
        val tombstone = report.copy(isTombstone = true, status = "REVOKED")
        val remotePayload = HealthSyncPayloadCodec.report(tombstone, emptyList())
        db.healthDao().upsertReport(report)
        db.healthDao().insertObservations(listOf(observation))
        db.healthDao().insertReportSnapshot(HealthReportSnapshotEntity("deleted", 1, 1, false, localPayload, hash(localPayload)))
        db.healthDao().upsertConflict(conflict(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, "deleted", localPayload, remotePayload))

        val result = SyncConflictResolver(db, FakeScheduler(db)).resolve(
            HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, "deleted", 1, ConflictChoice.REMOTE, now = 10,
        ) as ConflictResolutionResult.Resolved

        assertTrue(db.healthDao().report("deleted")!!.isTombstone)
        assertTrue(db.healthDao().observations("deleted").isEmpty())
        assertTrue(db.healthDao().reportSnapshots("deleted").first().isTombstone)
        assertEquals(2L, result.outbox.version)
    }

    @Test fun `measurement conflict tombstone cleans ready hashes only after successor commit`() = runTest {
        val local = measurement("measurement", 70.0)
        val localPayload = MeasurementRepository.canonicalPayload(local)
        val tombstonePayload = MeasurementRepository.canonicalPayload(local, isTombstone = true)
        db.bodyMeasurementDao().insert(local)
        db.measurementDocumentDao().upsert(
            MeasurementDocumentEntity("doc", "measurement", "ready-hash", "READY", "x.jpg", "image/jpeg", 1, 1),
        )
        db.healthDao().insertMeasurementSnapshot(MeasurementSnapshotEntity("measurement", 1, 1, false, localPayload, hash(localPayload)))
        db.healthDao().upsertConflict(conflict(HealthSyncCategory.MEASUREMENTS, "measurement", localPayload, tombstonePayload))
        val documents = RecordingDocuments()

        SyncConflictResolver(db, FakeScheduler(db), documents).resolve(
            HealthSyncCategory.MEASUREMENTS, "measurement", 1, ConflictChoice.REMOTE, now = 10,
        )

        assertEquals(listOf(setOf("ready-hash")), documents.cleaned)
        assertNull(db.bodyMeasurementDao().getById("measurement"))
        assertNull(db.measurementDocumentDao().document("doc"))
    }

    @Test fun `failed measurement tombstone resolution preserves ready metadata and skips cleanup`() = runTest {
        val local = measurement("measurement", 70.0)
        val localPayload = MeasurementRepository.canonicalPayload(local)
        val tombstonePayload = MeasurementRepository.canonicalPayload(local, isTombstone = true)
        db.bodyMeasurementDao().insert(local)
        db.measurementDocumentDao().upsert(
            MeasurementDocumentEntity("doc", "measurement", "ready-hash", "READY", "x.jpg", "image/jpeg", 1, 1),
        )
        db.healthDao().insertMeasurementSnapshot(MeasurementSnapshotEntity("measurement", 1, 1, false, localPayload, hash(localPayload)))
        db.healthDao().upsertConflict(conflict(HealthSyncCategory.MEASUREMENTS, "measurement", localPayload, tombstonePayload))
        // Resolving this marker would append exactly version 2. Force its final insert to fail,
        // proving that file cleanup is strictly after the enclosing Room transaction.
        db.healthDao().insertOutbox(
            HealthSyncOutboxEntity(HealthSyncCategory.MEASUREMENTS, "measurement", 2, "occupied", null, "occupied:2", 2),
        )
        val documents = RecordingDocuments()

        assertTrue(
            runCatching {
                SyncConflictResolver(db, FakeScheduler(db), documents).resolve(
                    HealthSyncCategory.MEASUREMENTS, "measurement", 1, ConflictChoice.REMOTE, now = 10,
                )
            }.isFailure,
        )
        assertEquals(local, db.bodyMeasurementDao().getById("measurement"))
        assertTrue(db.measurementDocumentDao().document("doc") != null)
        assertTrue(documents.cleaned.isEmpty())
        assertTrue(db.healthDao().conflict(HealthSyncCategory.MEASUREMENTS, "measurement", 1) != null)
    }

    private suspend fun resolveReport(choice: ConflictChoice, expectedRaw: String) {
        val localReport = HealthReportEntity("report", 1, 1, false, "CONFIRMED", "LAB", 1, "Local")
        val localObservation = HealthObservationEntity("local-observation", "report", 1, 1, false, 1, "Hb", "TEXT", "local Hb")
        val localPayload = HealthSyncPayloadCodec.report(localReport, listOf(localObservation))
        val remoteReport = localReport.copy(title = "Remote")
        val remoteObservation = localObservation.copy(syncId = "remote-observation", rawValue = "remote Hb")
        val remotePayload = HealthSyncPayloadCodec.report(remoteReport, listOf(remoteObservation))
        db.healthDao().upsertReport(localReport)
        db.healthDao().insertObservations(listOf(localObservation))
        db.healthDao().insertReportSnapshot(HealthReportSnapshotEntity("report", 1, 1, false, localPayload, hash(localPayload)))
        db.healthDao().insertOutbox(HealthSyncOutboxEntity(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, "report", 1, localPayload, hash(localPayload), "report:1", 1))
        db.healthDao().upsertConflict(conflict(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, "report", localPayload, remotePayload))
        val scheduler = FakeScheduler(db)

        val result = SyncConflictResolver(db, scheduler).resolve(
            HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, "report", 1, choice, now = 10,
        )

        val resolved = result as ConflictResolutionResult.Resolved
        assertEquals(2L, resolved.outbox.version)
        assertEquals("report:2", resolved.outbox.idempotencyKey)
        assertEquals(2L, db.healthDao().report("report")!!.version)
        assertEquals(1L, db.healthDao().report("report")!!.supersedesVersion)
        assertEquals(expectedRaw, db.healthDao().observations("report").single().rawValue)
        assertEquals(listOf(2L, 1L), db.healthDao().reportSnapshots("report").map { it.version })
        assertEquals(resolved.outbox, db.healthSyncOutboxDao().entry(resolved.outbox.category, "report", 2))
        assertNull(db.healthSyncOutboxDao().entry(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, "report", 1))
        assertNull(db.healthDao().conflict(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, "report", 1))
        assertEquals(listOf(resolved.outbox), scheduler.entries)
    }

    private suspend fun resolveRestriction(choice: ConflictChoice, expectedDescription: String) {
        val local = HealthRestrictionEntity("restriction", 1, 1, false, "ACTIVE", "CLINICIAN", 1, description = "Local restriction", originalText = "Original private wording")
        val localPayload = HealthSyncPayloadCodec.restriction(local)
        val remote = local.copy(description = "Remote restriction")
        val remotePayload = HealthSyncPayloadCodec.restriction(remote)
        db.healthDao().upsertRestriction(local)
        db.healthDao().insertRestrictionSnapshot(HealthRestrictionSnapshotEntity("restriction", 1, 1, false, localPayload, hash(localPayload), "Original private wording"))
        db.healthDao().insertOutbox(HealthSyncOutboxEntity(HealthSyncCategory.HEALTH_RESTRICTIONS, "restriction", 1, localPayload, hash(localPayload), "restriction:1", 1))
        db.healthDao().upsertConflict(conflict(HealthSyncCategory.HEALTH_RESTRICTIONS, "restriction", localPayload, remotePayload))
        val scheduler = FakeScheduler(db)

        val result = SyncConflictResolver(db, scheduler).resolve(
            HealthSyncCategory.HEALTH_RESTRICTIONS, "restriction", 1, choice, now = 10,
        )

        val resolved = result as ConflictResolutionResult.Resolved
        assertEquals(2L, resolved.outbox.version)
        assertEquals(expectedDescription, db.healthDao().restriction("restriction")!!.description)
        assertEquals("Original private wording", db.healthDao().restriction("restriction")!!.originalText)
        assertEquals(listOf(2L, 1L), db.healthDao().restrictionSnapshots("restriction").map { it.version })
        assertTrue(db.healthDao().restrictionSnapshots("restriction").all { it.originalText == "Original private wording" })
        assertEquals(resolved.outbox, db.healthSyncOutboxDao().entry(resolved.outbox.category, "restriction", 2))
        assertNull(db.healthSyncOutboxDao().entry(HealthSyncCategory.HEALTH_RESTRICTIONS, "restriction", 1))
        assertNull(db.healthDao().conflict(HealthSyncCategory.HEALTH_RESTRICTIONS, "restriction", 1))
        assertEquals(listOf(resolved.outbox), scheduler.entries)
    }

    private suspend fun resolveMeasurement(choice: ConflictChoice, expectedWeight: Double) {
        val local = measurement("measurement", 70.0)
        val localPayload = MeasurementRepository.canonicalPayload(local)
        val remote = measurement("measurement", 80.0)
        val remotePayload = MeasurementRepository.canonicalPayload(remote)
        db.bodyMeasurementDao().insert(local)
        db.healthDao().insertMeasurementSnapshot(MeasurementSnapshotEntity("measurement", 1, 1, false, localPayload, hash(localPayload)))
        db.healthDao().insertOutbox(HealthSyncOutboxEntity(HealthSyncCategory.MEASUREMENTS, "measurement", 1, localPayload, hash(localPayload), "measurement:1", 1))
        db.healthDao().upsertConflict(conflict(HealthSyncCategory.MEASUREMENTS, "measurement", localPayload, remotePayload))
        val scheduler = FakeScheduler(db)
        val measurementScheduler = FakeMeasurementScheduler()

        val result = SyncConflictResolver(db, scheduler, measurementUploadScheduler = measurementScheduler).resolve(
            HealthSyncCategory.MEASUREMENTS, "measurement", 1, choice, now = 10,
        )

        val resolved = result as ConflictResolutionResult.Resolved
        assertEquals(2L, resolved.outbox.version)
        assertEquals(expectedWeight, db.bodyMeasurementDao().getById("measurement")!!.weightKg)
        assertEquals(listOf(2L, 1L), db.healthDao().measurementSnapshots("measurement").map { it.version })
        assertEquals(resolved.outbox, db.healthSyncOutboxDao().entry(resolved.outbox.category, "measurement", 2))
        assertNull(db.healthSyncOutboxDao().entry(HealthSyncCategory.MEASUREMENTS, "measurement", 1))
        assertNull(db.healthDao().conflict(HealthSyncCategory.MEASUREMENTS, "measurement", 1))
        assertEquals(listOf("measurement"), measurementScheduler.ids)
    }

    private fun conflict(category: String, syncId: String, localPayload: String, remotePayload: String) =
        HealthSyncConflictEntity(category, syncId, 1, localPayload, hash(localPayload), remotePayload, hash(remotePayload), 2)

    private fun measurement(id: String, weight: Double) = BodyMeasurementEntity(id = id, measuredAt = 1, weightKg = weight)

    private fun hash(payload: String) = MeasurementRepository.sha256(payload)

    private class FakeScheduler(
        private val database: com.valerochka1337.valerochkagym.data.db.GymDatabase,
    ) : HealthSyncScheduler {
        val entries = mutableListOf<com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity>()
        override suspend fun schedule(entry: com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity) {
            check(database.healthSyncOutboxDao().entry(entry.category, entry.syncId, entry.version) == entry)
            check(database.healthDao().conflict(entry.category, entry.syncId, entry.version - 1) == null)
            entries += entry
        }
        override suspend fun schedulePending(category: SettingsCategory) = 0
        override suspend fun onCategoryChanged(category: SettingsCategory, enabled: Boolean) = Unit
    }

    private class FakeMeasurementScheduler : MeasurementUploadScheduler {
        val ids = mutableListOf<String>()
        override suspend fun schedule(measurementId: String) { ids += measurementId }
        override suspend fun retry(measurementId: String) = Unit
        override suspend fun scheduleAllPending() = 0
    }

    private class RecordingDocuments : MeasurementDocumentRepository by NoOpMeasurementDocumentRepository {
        val cleaned = mutableListOf<Set<String>>()

        override suspend fun cleanupUnreferenced(hashes: Set<String>) {
            cleaned += hashes
        }
    }
}
