package com.valerochka1337.valerochkagym.data

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.entity.HealthObservationEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportSnapshotEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRestrictionEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncOutboxEntity
import com.valerochka1337.valerochkagym.data.google.*
import com.valerochka1337.valerochkagym.data.health.ConflictChoice
import com.valerochka1337.valerochkagym.data.health.ConflictResolutionResult
import com.valerochka1337.valerochkagym.data.health.SyncConflictResolver
import com.valerochka1337.valerochkagym.data.health.HealthSyncPayloadCodec
import com.valerochka1337.valerochkagym.domain.health.HealthSheetRows
import com.valerochka1337.valerochkagym.data.settings.HealthSyncCategory as SettingCategory
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HealthSheetsRepositoryTest {
    private lateinit var db: GymDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GymDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() = db.close()
    @Test fun `report uploads one stable report and all observations then retry is idempotent`() = runTest {
        db.healthDao().upsertReport(HealthReportEntity("r",2,2,false,"FINAL","lab",1,"CBC"))
        db.healthDao().insertObservations(listOf(HealthObservationEntity("o1","r",1,2,false,1,"Hb","NUMBER","125"), HealthObservationEntity("o2","r",1,2,false,1,"WBC","NUMBER","5")))
        val api = FakeApi(); val settings = SettingsRepository(Store())
        settings.setSpreadsheetId("sheet"); settings.setHealthSyncEnabled(true); settings.setHealthSyncCategory(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS,true)
        val repository = HealthSheetsRepositoryImpl(api, Auth(), settings, db)
        val entry = HealthSyncOutboxEntity(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS,"r",2,HealthSyncPayloadCodec.report(db.healthDao().report("r")!!, db.healthDao().observations("r")),"h","r:2",2)
        assertEquals(UploadResult.Success, repository.upload(entry)); assertEquals(UploadResult.Success, repository.upload(entry))
        assertEquals(1, api.rows.getValue("HealthReports!A:P").count { it.first() == "r" })
        assertEquals(2, api.rows.getValue("HealthObservations!A:Q").count { it.first().startsWith("o") })
    }
    @Test fun `enable import treats absent sheets as nothing and valid empty header as nothing`() = runTest {
        val settings = SettingsRepository(Store()).also { it.setSpreadsheetId("sheet") }
        val api = FakeApi()
        val repository = HealthSheetsRepositoryImpl(api, Auth(), settings, db)
        assertEquals(HealthImportResult.NothingToImport, repository.importForEnable(SettingCategory.HEALTH_RESTRICTIONS))
        api.rows["HealthRestrictions!A:L"] = mutableListOf(HealthSheetRows.RESTRICTION_HEADER)
        assertEquals(HealthImportResult.NothingToImport, repository.importForEnable(SettingCategory.HEALTH_RESTRICTIONS))
    }
    @Test fun `enable import rejects malformed health headers without exposing row content`() = runTest {
        val settings = SettingsRepository(Store()).also { it.setSpreadsheetId("sheet") }
        val api = FakeApi()
        val repository = HealthSheetsRepositoryImpl(api, Auth(), settings, db)
        api.rows["HealthReports!A:P"] = mutableListOf(listOf("bad", "private"))
        assertTrue(repository.importForEnable(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS) is HealthImportResult.Failure)
        api.rows.clear(); api.rows["HealthReports!A:P"] = mutableListOf(HealthSheetRows.REPORT_HEADER); api.rows["HealthObservations!A:Q"] = mutableListOf(listOf("bad", "private"))
        assertTrue(repository.importForEnable(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS) is HealthImportResult.Failure)
        api.rows.clear(); api.rows["HealthRestrictions!A:L"] = mutableListOf(listOf("bad", "private"))
        assertTrue(repository.importForEnable(SettingCategory.HEALTH_RESTRICTIONS) is HealthImportResult.Failure)
    }
    @Test fun `enable import reports auth and network failures`() = runTest {
        val settings = SettingsRepository(Store()).also { it.setSpreadsheetId("sheet") }
        assertTrue(HealthSheetsRepositoryImpl(FakeApi(), NoAuth(), settings, db).importForEnable(SettingCategory.HEALTH_RESTRICTIONS) is HealthImportResult.Failure)
        assertTrue(HealthSheetsRepositoryImpl(FakeApi().apply { failReads = true }, Auth(), settings, db).importForEnable(SettingCategory.HEALTH_RESTRICTIONS) is HealthImportResult.Failure)
    }
    @Test fun `report import restores typed aggregate replaces higher version and accepts missing sheet safely`() = runTest {
        val api=FakeApi(); val settings=SettingsRepository(Store()); settings.setSpreadsheetId("sheet"); settings.setHealthSyncEnabled(true); settings.setHealthSyncCategory(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS,true)
        val report=HealthReportEntity("r",2,2,false,"FINAL","DOCUMENT",2,"CBC","note",1); val observation=HealthObservationEntity("o","r",3,2,false,2,"Hb","NUMBER","120","g/L","110-160","m","blood","lab","hb",2)
        api.rows["HealthReports!A:P"]=mutableListOf(HealthSheetRows.REPORT_HEADER, com.valerochka1337.valerochkagym.domain.health.HealthSheetRows.reportRow(report,"hash","r:2")); api.rows["HealthObservations!A:Q"]=mutableListOf(HealthSheetRows.OBSERVATION_HEADER, HealthSheetRows.observationRow(observation, report.version))
        val repository=HealthSheetsRepositoryImpl(api,Auth(),settings,db); assertEquals(1,repository.import(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS)); assertEquals(report,db.healthDao().report("r")); assertEquals(listOf(observation),db.healthDao().observations("r")); assertEquals(listOf(2L),db.healthDao().reportSnapshots("r").map { it.version }); assertEquals(0,repository.import(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS))
        api.rows.remove("HealthObservations!A:Q"); assertTrue(runCatching { repository.import(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS) }.isFailure); assertEquals(report,db.healthDao().report("r"))
    }

    @Test fun `incomplete report aggregate fails enable preflight and direct import without local writes`() = runTest {
        val api = FakeApi()
        val settings = SettingsRepository(Store()).also { it.setSpreadsheetId("sheet") }
        val repository = HealthSheetsRepositoryImpl(api, Auth(), settings, db)
        assertEquals(HealthImportResult.NothingToImport, repository.importForEnable(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS))
        val report = HealthReportEntity("incomplete", 1, 1, false, "FINAL", "LAB", 1, "CBC")
        api.rows["HealthReports!A:P"] = mutableListOf(
            HealthSheetRows.REPORT_HEADER,
            HealthSheetRows.reportRow(report, "hash", "incomplete:1"),
        )
        assertTrue(repository.importForEnable(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS) is HealthImportResult.Failure)
        assertEquals(null, db.healthDao().report("incomplete"))

        settings.setHealthSyncEnabled(true)
        settings.setHealthSyncCategory(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS, true)
        assertTrue(runCatching { repository.import(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS) }.isFailure)
        assertEquals(null, db.healthDao().report("incomplete"))

        api.rows["HealthObservations!A:Q"] = mutableListOf(HealthSheetRows.OBSERVATION_HEADER)
        assertTrue(repository.importForEnable(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS) is HealthImportResult.Failure)
        assertEquals(null, db.healthDao().report("incomplete"))
    }

    @Test fun `observation page provenance survives exact Sheets round trip`() = runTest {
        val api = FakeApi(); val settings = SettingsRepository(Store())
        settings.setSpreadsheetId("sheet"); settings.setHealthSyncEnabled(true); settings.setHealthSyncCategory(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS, true)
        val report = HealthReportEntity("page-report", 1, 1, false, "FINAL", "DOCUMENT", 1, "CBC")
        val observation = HealthObservationEntity("page-observation", "page-report", 1, 1, false, 1, "Hb", "NUMBER", "125", sourcePage = 2)
        api.rows["HealthReports!A:P"] = mutableListOf(HealthSheetRows.REPORT_HEADER, HealthSheetRows.reportRow(report, "hash", "page-report:1"))
        api.rows["HealthObservations!A:Q"] = mutableListOf(HealthSheetRows.OBSERVATION_HEADER, HealthSheetRows.observationRow(observation, report.version))
        val repository = HealthSheetsRepositoryImpl(api, Auth(), settings, db)
        assertEquals(1, repository.import(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS))
        assertEquals(2, db.healthDao().observations("page-report").single().sourcePage)
        assertEquals("2", api.rows.getValue("HealthObservations!A:Q")[1][16])
    }
    @Test fun `shuffled report revisions retain exact snapshot observations and expose only newest projection`() = runTest {
        val api = FakeApi(); val settings = SettingsRepository(Store())
        settings.setSpreadsheetId("sheet"); settings.setHealthSyncEnabled(true); settings.setHealthSyncCategory(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS, true)
        val v1 = HealthReportEntity("revision-report", 1, 10, false, "FINAL", "DOCUMENT", 10, "CBC v1")
        val v2 = v1.copy(version = 2, updatedAt = 20, title = "CBC v2", supersedesVersion = 1)
        val v1Observation = HealthObservationEntity("observation-v1", "revision-report", 1, 10, false, 10, "Hb", "NUMBER", "120", sourcePage = 1)
        val v2Observation = HealthObservationEntity("observation-v2", "revision-report", 1, 20, false, 20, "Hb", "NUMBER", "125", sourcePage = 2)
        api.rows["HealthReports!A:P"] = mutableListOf(
            HealthSheetRows.REPORT_HEADER,
            HealthSheetRows.reportRow(v2, "h2", "revision-report:2"),
            HealthSheetRows.reportRow(v1, "h1", "revision-report:1"),
        )
        api.rows["HealthObservations!A:Q"] = mutableListOf(
            HealthSheetRows.OBSERVATION_HEADER,
            HealthSheetRows.observationRow(v2Observation, 2),
            HealthSheetRows.observationRow(v1Observation, 1),
        )
        val repository = HealthSheetsRepositoryImpl(api, Auth(), settings, db)
        assertEquals(2, repository.import(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS))
        assertEquals(v2, db.healthDao().report("revision-report"))
        assertEquals(listOf(v2Observation), db.healthDao().observations("revision-report"))
        val snapshots = db.healthDao().reportSnapshots("revision-report")
        assertEquals(listOf(2L, 1L), snapshots.map { it.version })
        assertEquals(listOf(v1Observation), HealthSyncPayloadCodec.decodeReport(snapshots.last().canonicalPayload)!!.observations)
        assertEquals(listOf(v2Observation), HealthSyncPayloadCodec.decodeReport(snapshots.first().canonicalPayload)!!.observations)
        assertEquals(0, repository.import(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS))
    }

    @Test fun `report clear touches both managed sheets only`() = runTest {
        val api=FakeApi().apply {
            sheets += setOf("HealthReports", "HealthObservations", "HealthRestrictions", "Measurements")
            rows["HealthReports!A:P"] = mutableListOf(HealthSheetRows.REPORT_HEADER)
            rows["HealthObservations!A:Q"] = mutableListOf(HealthSheetRows.OBSERVATION_HEADER)
        }; val settings=SettingsRepository(Store()); settings.setSpreadsheetId("sheet"); val repository=HealthSheetsRepositoryImpl(api,Auth(),settings,db)
        assertEquals(RemoteClearResult.Success(listOf("HealthReports!A2:P", "HealthObservations!A2:Q")), repository.clearAfterConfirmation(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS)); assertEquals(listOf("HealthReports!A2:P","HealthObservations!A2:Q"),api.clears)
        assertEquals(setOf("HealthReports", "HealthObservations", "HealthRestrictions", "Measurements"), api.sheets)
    }
    @Test fun `medical clear accepts legacy observations without touching user columns`() = runTest {
        val api = FakeApi().apply {
            sheets += setOf("HealthReports", "HealthObservations")
            rows["HealthReports!A:P"] = mutableListOf(HealthSheetRows.REPORT_HEADER + "user_report_column")
            rows["HealthObservations!A:O"] = mutableListOf(HealthSheetRows.LEGACY_OBSERVATION_HEADER + "user_observation_column")
        }
        val settings = SettingsRepository(Store()).also { it.setSpreadsheetId("sheet") }

        assertEquals(
            RemoteClearResult.Success(listOf("HealthReports!A2:P", "HealthObservations!A2:O")),
            HealthSheetsRepositoryImpl(api, Auth(), settings, db)
                .clearAfterConfirmation(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS),
        )
        assertEquals(listOf("HealthReports!A2:P", "HealthObservations!A2:O"), api.clears)

        val interim = FakeApi().apply {
            sheets += setOf("HealthReports", "HealthObservations")
            rows["HealthReports!A:P"] = mutableListOf(HealthSheetRows.REPORT_HEADER)
            rows["HealthObservations!A:P"] = mutableListOf(HealthSheetRows.REPORT_VERSION_OBSERVATION_HEADER + "user_observation_column")
        }
        assertEquals(
            RemoteClearResult.Success(listOf("HealthReports!A2:P", "HealthObservations!A2:P")),
            HealthSheetsRepositoryImpl(interim, Auth(), settings, db)
                .clearAfterConfirmation(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS),
        )
        assertEquals(listOf("HealthReports!A2:P", "HealthObservations!A2:P"), interim.clears)
    }
    @Test fun `medical clear validates all present sheets before clearing and never creates absent ones`() = runTest {
        val api = FakeApi().apply {
            sheets += setOf("HealthReports", "HealthObservations")
            rows["HealthReports!A:P"] = mutableListOf(HealthSheetRows.REPORT_HEADER)
            rows["HealthObservations!A:Q"] = mutableListOf(listOf("user_owned", "private"))
        }
        val settings = SettingsRepository(Store()).also { it.setSpreadsheetId("sheet") }
        val repository = HealthSheetsRepositoryImpl(api, Auth(), settings, db)

        assertTrue(repository.clearAfterConfirmation(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS) is RemoteClearResult.Failure)
        assertTrue(api.clears.isEmpty())
        assertEquals(0, api.batchCalls)

        val absent = FakeApi()
        assertEquals(
            RemoteClearResult.Success(emptyList()),
            HealthSheetsRepositoryImpl(absent, Auth(), settings, db)
                .clearAfterConfirmation(SettingCategory.HEALTH_RESTRICTIONS),
        )
        assertTrue(absent.clears.isEmpty())
        assertEquals(0, absent.batchCalls)
    }
    @Test fun `report clear retains completed range when observations clear fails`() = runTest {
        val api = FakeApi().apply {
            sheets += setOf("HealthReports", "HealthObservations")
            rows["HealthReports!A:P"] = mutableListOf(HealthSheetRows.REPORT_HEADER)
            rows["HealthObservations!A:Q"] = mutableListOf(HealthSheetRows.OBSERVATION_HEADER)
            failClearAt = 2
        }
        val settings = SettingsRepository(Store()).also { it.setSpreadsheetId("sheet") }

        assertEquals(
            RemoteClearResult.Failure(
                "Нет сети при очистке Google Sheets",
                listOf("HealthReports!A2:P"),
                "HealthObservations!A2:Q",
            ),
            HealthSheetsRepositoryImpl(api, Auth(), settings, db)
                .clearAfterConfirmation(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS),
        )
        assertEquals(listOf("HealthReports!A2:P"), api.clears)
    }
    @Test fun `report clear revalidates observations header after reports clear`() = runTest {
        val api = FakeApi().apply {
            sheets += setOf("HealthReports", "HealthObservations")
            rows["HealthReports!A:P"] = mutableListOf(HealthSheetRows.REPORT_HEADER)
            rows["HealthObservations!A:Q"] = mutableListOf(HealthSheetRows.OBSERVATION_HEADER)
            mutateHeaderAfterClear = 1 to ("HealthObservations!A:Q" to listOf("user_owned", "column"))
        }
        val settings = SettingsRepository(Store()).also { it.setSpreadsheetId("sheet") }

        assertEquals(
            RemoteClearResult.Failure(
                "Заголовок листа HealthObservations изменён вручную — очистка отменена",
                clearedRanges = listOf("HealthReports!A2:P"),
                failedRange = "HealthObservations!A2:Q",
            ),
            HealthSheetsRepositoryImpl(api, Auth(), settings, db)
                .clearAfterConfirmation(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS),
        )
        assertEquals(listOf("HealthReports!A2:P"), api.clears)
    }
    @Test fun `restriction clear returns typed managed range result`() = runTest {
        val api = FakeApi().apply {
            sheets += "HealthRestrictions"
            rows["HealthRestrictions!A:L"] = mutableListOf(HealthSheetRows.RESTRICTION_HEADER)
        }
        val settings = SettingsRepository(Store()).also { it.setSpreadsheetId("sheet") }

        assertEquals(
            RemoteClearResult.Success(listOf("HealthRestrictions!A2:L")),
            HealthSheetsRepositoryImpl(api, Auth(), settings, db)
                .clearAfterConfirmation(SettingCategory.HEALTH_RESTRICTIONS),
        )
    }
    @Test fun `restriction clear returns typed failure without completed ranges`() = runTest {
        val api = FakeApi().apply {
            sheets += "HealthRestrictions"
            rows["HealthRestrictions!A:L"] = mutableListOf(HealthSheetRows.RESTRICTION_HEADER)
            failClearAt = 1
        }
        val settings = SettingsRepository(Store()).also { it.setSpreadsheetId("sheet") }

        assertEquals(
            RemoteClearResult.Failure(
                "Нет сети при очистке Google Sheets",
                failedRange = "HealthRestrictions!A2:L",
            ),
            HealthSheetsRepositoryImpl(api, Auth(), settings, db)
                .clearAfterConfirmation(SettingCategory.HEALTH_RESTRICTIONS),
        )
        assertTrue(api.clears.isEmpty())
    }
    @Test fun `imported restriction has no local original wording`() = runTest {
        val api=FakeApi(); val settings=SettingsRepository(Store()); settings.setSpreadsheetId("sheet"); settings.setHealthSyncEnabled(true); settings.setHealthSyncCategory(SettingCategory.HEALTH_RESTRICTIONS,true)
        val remote=HealthRestrictionEntity("remote",1,2,false,"ACTIVE","CLINICIAN",2,description="No sprint")
        api.rows["HealthRestrictions!A:L"]=mutableListOf(HealthSheetRows.RESTRICTION_HEADER,HealthSheetRows.restrictionRow(remote,"hash","remote:1"))
        assertEquals(1,HealthSheetsRepositoryImpl(api,Auth(),settings,db).import(SettingCategory.HEALTH_RESTRICTIONS))
        assertEquals(null,db.healthDao().restriction("remote")!!.originalText)
    }
    @Test fun `newer remote restriction preserves local only original wording`() = runTest {
        val local = HealthRestrictionEntity("restriction", 1, 1, false, "ACTIVE", "USER", 1, description = "No sprint", originalText = "точная исходная формулировка")
        db.healthDao().upsertRestriction(local)
        val remote = local.copy(version = 2, updatedAt = 2, description = "No sprint or jumps", originalText = null)
        val api = FakeApi(); val settings = SettingsRepository(Store())
        settings.setSpreadsheetId("sheet"); settings.setHealthSyncEnabled(true); settings.setHealthSyncCategory(SettingCategory.HEALTH_RESTRICTIONS, true)
        api.rows["HealthRestrictions!A:L"] = mutableListOf(HealthSheetRows.RESTRICTION_HEADER, HealthSheetRows.restrictionRow(remote, "hash", "restriction:2"))
        assertEquals(1, HealthSheetsRepositoryImpl(api, Auth(), settings, db).import(SettingCategory.HEALTH_RESTRICTIONS))
        assertEquals("точная исходная формулировка", db.healthDao().restriction("restriction")!!.originalText)
    }
    @Test fun `equal divergent health aggregate stores conflict and leaves local unchanged`() = runTest {
        val local=HealthReportEntity("same",2,2,false,"FINAL","LAB",2,"Local"); db.healthDao().upsertReport(local); val localObs=HealthObservationEntity("lo","same",1,2,false,2,"Hb","NUMBER","120"); db.healthDao().insertObservations(listOf(localObs))
        val api=FakeApi(); val settings=SettingsRepository(Store()); settings.setSpreadsheetId("sheet"); settings.setHealthSyncEnabled(true); settings.setHealthSyncCategory(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS,true)
        val remote=local.copy(title="Remote"); api.rows["HealthReports!A:P"]=mutableListOf(HealthSheetRows.REPORT_HEADER,HealthSheetRows.reportRow(remote,"different","same:2")); api.rows["HealthObservations!A:Q"]=mutableListOf(HealthSheetRows.OBSERVATION_HEADER,HealthSheetRows.observationRow(localObs.copy(rawValue="999"), remote.version))
        val repository=HealthSheetsRepositoryImpl(api,Auth(),settings,db); assertEquals(0,repository.import(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS)); assertEquals(local,db.healthDao().report("same")); assertEquals(listOf(localObs),db.healthDao().observations("same")); assertEquals(1,db.healthDao().conflicts().size)
    }
    @Test fun `missing remote payload hash creates a resolvable report conflict`() = runTest {
        val local = HealthReportEntity("missing-hash", 1, 1, false, "FINAL", "LAB", 1, "Local")
        val localObservation = HealthObservationEntity("local-observation", "missing-hash", 1, 1, false, 1, "Hb", "NUMBER", "120")
        db.healthDao().upsertReport(local)
        db.healthDao().insertObservations(listOf(localObservation))
        db.healthDao().insertReportSnapshot(
            HealthReportSnapshotEntity("missing-hash", 1, 1, false, HealthSyncPayloadCodec.report(local, listOf(localObservation)), "local-hash"),
        )
        val remote = local.copy(title = "Remote")
        val remoteObservation = localObservation.copy(rawValue = "121")
        val api = FakeApi(); val settings = SettingsRepository(Store())
        settings.setSpreadsheetId("sheet"); settings.setHealthSyncEnabled(true)
        settings.setHealthSyncCategory(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS, true)
        api.rows["HealthReports!A:P"] = mutableListOf(
            HealthSheetRows.REPORT_HEADER,
            HealthSheetRows.reportRow(remote, "", "missing-hash:1"),
        )
        api.rows["HealthObservations!A:Q"] = mutableListOf(
            HealthSheetRows.OBSERVATION_HEADER,
            HealthSheetRows.observationRow(remoteObservation, 1),
        )

        assertEquals(0, HealthSheetsRepositoryImpl(api, Auth(), settings, db)
            .import(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS))
        val conflict = db.healthDao().conflict(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, "missing-hash", 1)!!
        assertEquals(64, conflict.remotePayloadHash!!.length)
        assertTrue(
            SyncConflictResolver(db).resolve(
                HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS,
                "missing-hash",
                1,
                ConflictChoice.REMOTE,
                now = 2,
            ) is ConflictResolutionResult.Resolved,
        )
        assertEquals("Remote", db.healthDao().report("missing-hash")!!.title)
    }

    @Test fun `malformed health lifecycle rows reject the whole import before writing`() = runTest {
        val api = FakeApi(); val settings = SettingsRepository(Store())
        settings.setSpreadsheetId("sheet"); settings.setHealthSyncEnabled(true)
        settings.setHealthSyncCategory(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS, true)
        val valid = HealthReportEntity("valid", 1, 1, false, "FINAL", "LAB", 1, "CBC")
        val malformed = valid.copy(syncId = "bad-report", version = 0, status = "UNKNOWN")
        api.rows["HealthReports!A:P"] = mutableListOf(
            HealthSheetRows.REPORT_HEADER,
            HealthSheetRows.reportRow(valid, "hash", "valid:1"),
            HealthSheetRows.reportRow(malformed, "hash", "bad-report:0"),
        )
        api.rows["HealthObservations!A:Q"] = mutableListOf(HealthSheetRows.OBSERVATION_HEADER)

        assertTrue(runCatching {
            HealthSheetsRepositoryImpl(api, Auth(), settings, db)
                .import(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS)
        }.isFailure)
        assertEquals(null, db.healthDao().report("valid"))

        settings.setHealthSyncCategory(SettingCategory.HEALTH_RESTRICTIONS, true)
        val restriction = HealthRestrictionEntity("valid-restriction", 1, 1, false, "ACTIVE", "USER", 1, description = "No sprint")
        val invalidRestriction = restriction.copy(
            syncId = "bad-restriction",
            status = "LIFTED",
            isTombstone = false,
            source = "UNKNOWN",
            confirmedAt = 0,
        )
        api.rows["HealthRestrictions!A:L"] = mutableListOf(
            HealthSheetRows.RESTRICTION_HEADER,
            HealthSheetRows.restrictionRow(restriction, "hash", "valid-restriction:1"),
            HealthSheetRows.restrictionRow(invalidRestriction, "hash", "bad-restriction:1"),
        )

        assertTrue(runCatching {
            HealthSheetsRepositoryImpl(api, Auth(), settings, db)
                .import(SettingCategory.HEALTH_RESTRICTIONS)
        }.isFailure)
        assertEquals(null, db.healthDao().restriction("valid-restriction"))
    }
    @Test fun `higher health tombstone hides current aggregate and preserves sync audit`() = runTest {
        val local=HealthReportEntity("gone",1,1,false,"FINAL","LAB",1,"CBC"); val localObservation=HealthObservationEntity("old","gone",1,1,false,1,"Hb","NUMBER","120"); db.healthDao().upsertReport(local); db.healthDao().insertObservations(listOf(localObservation)); db.healthDao().insertReportSnapshot(HealthReportSnapshotEntity("gone",1,1,false,HealthSyncPayloadCodec.report(local,listOf(localObservation)),"h1"))
        val api=FakeApi(); val settings=SettingsRepository(Store()); settings.setSpreadsheetId("sheet"); settings.setHealthSyncEnabled(true); settings.setHealthSyncCategory(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS,true)
        val tombstone=local.copy(version=2,updatedAt=2,isTombstone=true,status="REVOKED"); api.rows["HealthReports!A:P"]=mutableListOf(HealthSheetRows.REPORT_HEADER,HealthSheetRows.reportRow(tombstone,"h2","gone:2")); api.rows["HealthObservations!A:Q"]=mutableListOf(HealthSheetRows.OBSERVATION_HEADER)
        assertEquals(1,HealthSheetsRepositoryImpl(api,Auth(),settings,db).import(SettingCategory.HEALTH_REPORTS_AND_OBSERVATIONS)); assertTrue(db.healthDao().observeLiveReports().first().none { it.syncId=="gone" }); assertTrue(db.healthDao().observations("gone").isEmpty()); assertEquals(2,db.healthDao().report("gone")!!.version); assertEquals(listOf(2L, 1L), db.healthDao().reportSnapshots("gone").map { it.version }); assertTrue(db.healthDao().reportSnapshots("gone").first().isTombstone)
    }
    private class Auth : GoogleAuth { override suspend fun signIn(activity: android.app.Activity)=Result.success("a"); override suspend fun authorize(activity: android.app.Activity)=AuthorizeOutcome.Granted; override suspend fun getAccessToken()=TokenResult.Success("t"); override suspend fun signOut()=Unit }
    private class NoAuth : GoogleAuth { override suspend fun signIn(activity: android.app.Activity)=Result.success("a"); override suspend fun authorize(activity: android.app.Activity)=AuthorizeOutcome.Granted; override suspend fun getAccessToken()=TokenResult.Failed(null); override suspend fun signOut()=Unit }
    private class Store : DataStore<Preferences> { private val state=MutableStateFlow(emptyPreferences()); override val data:Flow<Preferences> = state; override suspend fun updateData(transform:suspend(Preferences)->Preferences)=transform(state.value).also{state.value=it} }
    private class FakeApi : SheetsApi {
        var failReads = false
        var failClearAt: Int? = null
        var mutateHeaderAfterClear: Pair<Int, Pair<String, List<String>>>? = null
        val clears=mutableListOf<String>()
        val sheets=mutableSetOf<String>(); val rows=mutableMapOf<String,MutableList<List<String>>>()
        var batchCalls = 0
        override suspend fun getSpreadsheet(bearer:String,spreadsheetId:String,fields:String)=SpreadsheetDto(sheets.map{SheetDto(SheetPropertiesDto(it))})
        override suspend fun batchUpdate(bearer:String,spreadsheetId:String,body:BatchUpdateRequestDto):JsonElement { batchCalls++; body.requests.mapNotNull{it.addSheet?.properties?.title}.forEach(sheets::add); return JsonNull }
        override suspend fun getValues(bearer:String,spreadsheetId:String,range:String):ValueRangeDto { if(failReads) throw IOException("network"); val match=rows[range] ?: rows.entries.firstOrNull{it.key.substringBefore("!")==range.substringBefore("!") }?.value; return ValueRangeDto(match) }
        override suspend fun appendValues(bearer:String,spreadsheetId:String,range:String,body:AppendValuesDto,valueInputOption:String,insertDataOption:String):JsonElement { val parsed=body.values.map{(it as JsonArray).map{c->(c as JsonPrimitive).content}}; rows.getOrPut(range){mutableListOf()}.addAll(parsed); return JsonNull }
        override suspend fun clearValues(bearer:String,spreadsheetId:String,range:String,body:ClearValuesDto):JsonElement {
            if (failClearAt == clears.size + 1) throw IOException("clear failed")
            clears += range
            mutateHeaderAfterClear?.takeIf { (at, _) -> clears.size == at }?.second?.let { (key, header) ->
                rows[key] = mutableListOf(header)
            }
            return JsonNull
        }
        override suspend fun updateValues(bearer:String,spreadsheetId:String,range:String,body:UpdateValuesDto,valueInputOption:String):JsonElement { rows.getOrPut(range.substringBefore("!")+"!A:L"){mutableListOf()}.addAll(body.values.map{(it as JsonArray).map{c->(c as JsonPrimitive).content}}); return JsonNull }
    }
}
