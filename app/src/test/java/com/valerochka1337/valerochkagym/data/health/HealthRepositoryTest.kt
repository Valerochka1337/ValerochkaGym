package com.valerochka1337.valerochkagym.data.health

import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.domain.health.ConfirmedHealthRestriction
import com.valerochka1337.valerochkagym.domain.health.HealthInformationSource
import com.valerochka1337.valerochkagym.domain.health.HealthObservationDraft
import com.valerochka1337.valerochkagym.domain.health.HealthRawValue
import com.valerochka1337.valerochkagym.domain.health.HealthReportDraft
import com.valerochka1337.valerochkagym.domain.health.HealthRestrictionDraft
import com.valerochka1337.valerochkagym.domain.health.HealthRestrictionState
import com.valerochka1337.valerochkagym.domain.health.HealthRestrictionProposal
import com.valerochka1337.valerochkagym.domain.health.ConfirmRestrictionProposalsCommand
import com.valerochka1337.valerochkagym.domain.health.ReportSaveCommand
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity
import com.valerochka1337.valerochkagym.worker.HealthSyncScheduler
import com.valerochka1337.valerochkagym.data.settings.HealthSyncCategory as SettingsCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthRepositoryTest : RoomDaoTest() {
    @Test
    fun `committed report revoke and restriction schedule their exact durable outbox entries`() = runTest {
        val scheduler = FakeScheduler()
        val repository = HealthRepository(db, scheduler)
        val report = repository.saveConfirmedReport(draft("A"), now = 100)
        repository.revoke(report.syncId, now = 200)
        repository.saveRestriction(
            ConfirmedHealthRestriction(
                syncId = "restriction", updatedAt = 300,
                draft = HealthRestrictionDraft("No press", HealthRestrictionState.TEMPORARY, HealthInformationSource.CLINICIAN, confirmedAt = 300),
            ),
        )

        assertEquals(
            listOf(
                "HEALTH_REPORTS_AND_OBSERVATIONS:${report.syncId}:1",
                "HEALTH_REPORTS_AND_OBSERVATIONS:${report.syncId}:2",
                "HEALTH_RESTRICTIONS:restriction:1",
            ),
            scheduler.entries.map { "${it.category}:${it.syncId}:${it.version}" },
        )
        assertEquals(3, db.healthSyncOutboxDao().pending("HEALTH_REPORTS_AND_OBSERVATIONS").size +
            db.healthSyncOutboxDao().pending("HEALTH_RESTRICTIONS").size)
    }

    @Test
    fun `validation failure never schedules an uncommitted health entry`() = runTest {
        val scheduler = FakeScheduler()
        val repository = HealthRepository(db, scheduler)

        runCatching { repository.saveConfirmedReport(HealthReportDraft("", "manual", 1, emptyList()), now = 1) }

        assertTrue(scheduler.entries.isEmpty())
        assertTrue(db.healthDao().observeLiveReports().first().isEmpty())
        assertEquals(0, tableCount("health_report_snapshots"))
        assertEquals(0, tableCount("health_sync_outbox"))
    }
    @Test
    fun `unknown observation names remain raw and correction retains predecessor history`() = runTest {
        val repository = HealthRepository(db)
        val first = repository.saveConfirmedReport(draft("Unmapped lab value"), now = 100)
        val corrected = repository.correct(first.syncId, draft("Unmapped lab value corrected"), now = 200)

        assertEquals(first.syncId, corrected.syncId)
        assertEquals(first.version, corrected.supersedesVersion)
        assertEquals("Unmapped lab value corrected", db.healthDao().observations(first.syncId).single().rawName)
        assertEquals(null, db.healthDao().observations(first.syncId).single().canonicalKey)
        assertEquals(listOf(first.syncId), db.healthDao().observeLiveReports().first().map { it.syncId })

        repository.revoke(corrected.syncId, now = 300)
        assertTrue(db.healthDao().observeLiveReports().first().isEmpty())
    }

    @Test
    fun `confirmed report preserves page provenance in immutable observation snapshots`() = runTest {
        val repository = HealthRepository(db)
        val created = repository.saveConfirmedReport(
            HealthReportDraft("CBC", "DOCUMENT", 10, listOf(
                HealthObservationDraft("Hb", HealthRawValue.Number(125.0, "125"), observedAt = 10, sourcePage = 2),
            )),
            now = 100,
        )
        val observation = db.healthDao().observations(created.syncId).single()
        assertEquals(2, observation.sourcePage)
        assertEquals(2, HealthSyncPayloadCodec.decodeReport(db.healthDao().reportSnapshots(created.syncId).single().canonicalPayload)!!.observations.single().sourcePage)
    }

    @Test
    fun `stable report correction and revoke retain exact snapshots while hiding the projection`() = runTest {
        val repository = HealthRepository(db)
        val created = repository.saveConfirmedReport(draft("v1"), now = 100)
        val corrected = repository.correct(created.syncId, draft("v2"), now = 200)

        val current = db.healthDao().report(created.syncId)!!
        assertEquals(created.syncId, corrected.syncId)
        assertEquals(2L, current.version)
        assertEquals(1L, current.supersedesVersion)
        assertEquals(listOf(2L, 1L), db.healthDao().reportSnapshots(created.syncId).map { it.version })
        assertEquals("v2", db.healthDao().observations(created.syncId).single().rawName)

        repository.revoke(created.syncId, now = 300)

        assertTrue(db.healthDao().observeLiveReports().first().isEmpty())
        assertTrue(db.healthDao().observeObservationsForHistory().first().isEmpty())
        assertTrue(db.healthDao().observations(created.syncId).isEmpty())
        assertEquals(listOf(3L, 2L, 1L), db.healthDao().reportSnapshots(created.syncId).map { it.version })
        assertTrue(db.healthDao().reportSnapshots(created.syncId).first().isTombstone)
        assertEquals(2L, db.healthDao().report(created.syncId)!!.supersedesVersion)
    }

    @Test
    fun `unconfirmed or incomplete drafts never write rows and restriction requires explicit confirmation`() = runTest {
        val repository = HealthRepository(db)
        try {
            repository.saveConfirmedReport(HealthReportDraft("", "manual", 1, emptyList()), now = 1)
        } catch (_: IllegalArgumentException) {
            // Validation happens before entering a Room transaction.
        }
        assertTrue(db.healthDao().observeLiveReports().first().isEmpty())

        repository.saveRestriction(
            ConfirmedHealthRestriction(
                syncId = "restriction", updatedAt = 10,
                draft = HealthRestrictionDraft("No overhead press", HealthRestrictionState.TEMPORARY, HealthInformationSource.CLINICIAN, confirmedAt = 10),
            ),
        )
        assertEquals("TEMPORARY", db.healthDao().observeLiveRestrictions().first().single().status)
    }

    @Test
    fun `restriction retains the first exact original wording across structured edits`() = runTest {
        val repository = HealthRepository(db)
        val first = ConfirmedHealthRestriction(
            syncId = "restriction-original", version = 1, updatedAt = 10,
            draft = HealthRestrictionDraft("No sprint", HealthRestrictionState.ACTIVE, HealthInformationSource.USER, confirmedAt = 10),
        )
        repository.saveRestriction(first, originalText = "Врач попросил пока не бегать")
        repository.saveRestriction(
            first.copy(
                version = 2, updatedAt = 20,
                draft = first.draft.copy(description = "No sprint or jumps", confirmedAt = 20),
            ),
            originalText = "attempted replacement",
        )

        assertEquals("Врач попросил пока не бегать", db.healthDao().restriction("restriction-original")!!.originalText)
        assertEquals(
            listOf("Врач попросил пока не бегать", "Врач попросил пока не бегать"),
            db.healthDao().restrictionSnapshots("restriction-original").map { it.originalText },
        )
    }

    @Test
    fun `retrying stable report command returns one revision and preserves raw operator text`() = runTest {
        val repository = HealthRepository(db)
        val draft = HealthReportDraft("Lab", "manual", 20, listOf(
            HealthObservationDraft("marker", HealthRawValue.NumberWithOperator(">=", 2.0, ">=02.00"), canonicalKey = "suggested", observedAt = 10),
        ), collectedAt = 10, conditions = "fasting", originalExpected = true)
        val command = ReportSaveCommand("operation", "report", draft)

        val first = repository.saveReport(command, 100)
        val replay = repository.saveReport(command, 200)

        assertEquals(first.report.version, replay.report.version)
        assertEquals(1, db.healthDao().reportSnapshots("report").size)
        val observation = db.healthDao().observations("report").single()
        assertEquals(">=02.00", observation.rawValue)
        assertEquals(null, observation.canonicalKey)
        assertEquals(10L, db.healthDao().report("report")!!.collectedAt)
        assertEquals("fasting", db.healthDao().report("report")!!.conditions)
        assertTrue(db.healthDao().report("report")!!.originalExpected)
    }

    @Test
    fun `correction retains observation identity and proposal confirmation is atomic`() = runTest {
        val repository = HealthRepository(db)
        val created = repository.saveConfirmedReport(draft("v1"), 10)
        val original = db.healthDao().observations(created.syncId).single().syncId
        val corrected = repository.correct(created.syncId, repository.correctionDraft(created.syncId)!!.copy(title = "v2"), 20)

        assertEquals(original, db.healthDao().observations(created.syncId).single().syncId)
        assertEquals(created.version, corrected.correctionOfVersion)
        repository.confirmRestrictionProposals(
            ConfirmRestrictionProposalsCommand("restrictions", listOf(
                HealthRestrictionProposal("one", "No sprint", HealthRestrictionState.TEMPORARY, HealthInformationSource.USER),
                HealthRestrictionProposal("two", "No jump", HealthRestrictionState.ACTIVE, HealthInformationSource.CLINICIAN),
            )), 30,
        )
        assertEquals(listOf("one", "two"), db.healthDao().observeLiveRestrictions().first().map { it.syncId }.sorted())
        assertEquals(2, db.healthSyncOutboxDao().pending("HEALTH_RESTRICTIONS").size)
    }

    @Test
    fun `correction target exposes the current revision and stable observation identity`() = runTest {
        val repository = HealthRepository(db)
        val created = repository.saveConfirmedReport(draft("v1"), 10)

        val target = repository.correctionTarget(created.syncId)

        assertEquals(created.version, target!!.version)
        assertEquals(created.syncId, target.syncId)
        assertEquals(
            db.healthDao().observations(created.syncId).single().syncId,
            target.draft.observations.single().observationSyncId,
        )
    }

    private fun draft(name: String) = HealthReportDraft(
        title = "Lab", provenance = "manual", reportedAt = 10,
        observations = listOf(HealthObservationDraft(name, HealthRawValue.Text("kept exactly"), observedAt = 10)),
    )

    private class FakeScheduler : HealthSyncScheduler {
        val entries = mutableListOf<HealthSyncOutboxEntity>()
        override suspend fun schedule(entry: HealthSyncOutboxEntity) { entries += entry }
        override suspend fun schedulePending(category: SettingsCategory) = 0
        override suspend fun onCategoryChanged(category: SettingsCategory, enabled: Boolean) = Unit
    }
}
