package com.valerochka1337.valerochkagym.data.health

import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthObservationEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRestrictionEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthDocumentEntity
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.MeasurementDocumentEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportSnapshotEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRestrictionSnapshotEntity
import com.valerochka1337.valerochkagym.data.measurements.MeasurementRepository
import com.valerochka1337.valerochkagym.data.health.MeasurementArchiveGroup
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.security.MessageDigest
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HealthArchiveExporterTest : RoomDaoTest() {
    @Test fun `empty selected archive has deterministic manifest and no source mutation`() = runTest {
        val exporter = exporter()
        val first = ByteArrayOutputStream(); val second = ByteArrayOutputStream()
        assertTrue(exporter.export(first, HealthArchiveSelection()) is HealthArchiveExportResult.Success)
        assertTrue(exporter.export(second, HealthArchiveSelection()) is HealthArchiveExportResult.Success)
        assertTrue(first.toByteArray().contentEquals(second.toByteArray()))
        val names = ZipInputStream(first.toByteArray().inputStream()).use { zip -> generateSequence { zip.nextEntry?.name }.toList() }
        assertEquals(listOf("manifest.json"), names)
        assertTrue(db.healthDao().observeLiveReports().first().isEmpty())
    }

    @Test fun `selected confirmed records produce stable linked zip and exclude unsafe originals`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dao = db.healthDao()
        val report = HealthReportEntity("report-b", 2, 20, false, "CONFIRMED", "LAB", 200, "CBC")
        dao.upsertReport(report)
        dao.upsertReport(HealthReportEntity("draft-never", 1, 1, true, "REVOKED", "AI raw response", 1, "draft"))
        val observation = HealthObservationEntity("obs-b", "report-b", 3, 20, false, 200, "Hb", "NUMBER", "120", "g/L", sourcePage = 2)
        dao.insertObservations(listOf(observation))
        val reportPayload = HealthSyncPayloadCodec.report(report, listOf(observation))
        dao.insertReportSnapshot(HealthReportSnapshotEntity("report-b", 2, 20, false, reportPayload, digest(reportPayload.encodeToByteArray())))
        val restriction = HealthRestrictionEntity("restriction-a", 4, 20, false, "ACTIVE", "CLINICIAN", 200, null, null, "No sprint", originalText = "No running until review")
        dao.upsertRestriction(restriction)
        val restrictionPayload = HealthSyncPayloadCodec.restriction(restriction)
        dao.insertRestrictionSnapshot(HealthRestrictionSnapshotEntity("restriction-a", 4, 20, false, restrictionPayload, digest(restrictionPayload.encodeToByteArray()), "No running until review"))
        val bytes = "private-original".encodeToByteArray(); val hash = digest(bytes)
        val dir = File(context.noBackupFilesDir, "health_documents").apply { mkdirs() }
        File(dir, hash).writeBytes(bytes)
        dao.upsertDocument(HealthDocumentEntity("ready", "report-b", hash, "READY", "report.pdf", "application/pdf", bytes.size.toLong(), null, 1))
        dao.upsertDocument(HealthDocumentEntity("pending", "report-b", "deadbeef", "PENDING", "pending.pdf", "application/pdf", 1, null, 1))
        dao.upsertDocument(HealthDocumentEntity("tampered", "report-b", "bad", "READY", "bad.pdf", "application/pdf", 1, null, 1))
        val exporter = exporter(context)
        val selection = HealthArchiveSelection(reportIds = setOf("report-b"), restrictionIds = setOf("restriction-a"), documentIds = setOf("ready", "tampered"), observationIds = setOf("obs-b"))
        val one = ByteArrayOutputStream(); val two = ByteArrayOutputStream()
        val result = exporter.export(one, selection) as HealthArchiveExportResult.Success
        exporter.export(two, selection)
        assertTrue(one.toByteArray().contentEquals(two.toByteArray()))
        assertEquals(listOf("health:tampered:ready"), result.missingOriginals)
        val entries = unzip(one.toByteArray())
        val manifest = entries.getValue("manifest.json").decodeToString()
        assertTrue(entries.keys.any { it.startsWith("health-documents/ready-") })
        assertTrue("stable links", manifest.contains("report-b") && manifest.contains("obs-b") && manifest.contains("restriction-a") && manifest.contains(hash))
        assertTrue("page provenance", manifest.contains("\"sourcePage\":2"))
        assertTrue("explicit local restriction export", manifest.contains("No running until review"))
        assertTrue("primary only", !manifest.contains("draft-never") && !manifest.contains("AI raw response") && !manifest.contains("workout"))
        assertTrue("pending excluded", entries.keys.none { it.contains("pending") })
        assertTrue(File(dir, hash).readBytes().contentEquals(bytes))
        assertEquals("CBC", dao.report("report-b")!!.title)
    }

    @Test fun `explicit archive selection excludes only deselected report and restriction`() = runTest {
        val dao = db.healthDao()
        dao.upsertReport(HealthReportEntity("report-1", 1, 1, false, "CONFIRMED", "LAB", 10, "CBC"))
        dao.upsertReport(HealthReportEntity("report-2", 1, 2, false, "CONFIRMED", "LAB", 20, "Chemistry"))
        dao.upsertRestriction(HealthRestrictionEntity("restriction-1", 1, 1, false, "ACTIVE", "USER", 10, description = "No sprint"))
        dao.upsertRestriction(HealthRestrictionEntity("restriction-2", 1, 2, false, "ACTIVE", "USER", 20, description = "No jump"))
        val output = ByteArrayOutputStream()
        val exporter = exporter()

        exporter.export(output, HealthArchiveSelection(reportIds = setOf("report-2"), restrictionIds = setOf("restriction-1")))

        val manifest = unzip(output.toByteArray()).getValue("manifest.json").decodeToString()
        assertTrue(manifest.contains("report-2"))
        assertTrue(manifest.contains("restriction-1"))
        assertTrue(!manifest.contains("report-1"))
        assertTrue(!manifest.contains("restriction-2"))
    }

    @Test fun `selected measurement exports exact snapshot conditions and only verified measurement originals`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val measurement = BodyMeasurementEntity(
            "measurement", 100, weightKg = 70.0, bodyFatMassKg = null, waistHipRatio = null,
            afterMeal = true, afterWorkout = true, unusualHydration = true, conditionNote = "после сауны",
        )
        db.bodyMeasurementDao().insert(measurement)
        MeasurementRepository(db).save(measurement, now = 100)
        val bytes = "measurement-original".encodeToByteArray(); val hash = digest(bytes)
        val dir = File(context.noBackupFilesDir, "measurement_documents").apply { mkdirs() }
        File(dir, hash).writeBytes(bytes)
        db.measurementDocumentDao().upsert(MeasurementDocumentEntity("ready-measurement", "measurement", hash, "READY", "inbody.jpg", "image/jpeg", bytes.size.toLong(), 1))
        db.measurementDocumentDao().upsert(MeasurementDocumentEntity("pending-measurement", "measurement", "pending", "PENDING", "pending.jpg", "image/jpeg", 1, 1))
        db.measurementDocumentDao().upsert(MeasurementDocumentEntity("bad-measurement", "measurement", "bad", "READY", "bad.jpg", "image/jpeg", 1, 1))

        val output = ByteArrayOutputStream()
        val result = exporter(context).export(output, HealthArchiveSelection(measurementIds = setOf("measurement"), documentIds = setOf("ready-measurement", "bad-measurement"))) as HealthArchiveExportResult.Success
        val entries = unzip(output.toByteArray()); val manifest = entries.getValue("manifest.json").decodeToString()

        assertEquals(listOf("measurement:bad-measurement:ready"), result.missingOriginals)
        assertTrue(entries.keys.any { it.startsWith("measurement-documents/ready-measurement-") })
        assertTrue(manifest.contains("\"schemaVersion\":2") && manifest.contains("\"afterMeal\":true") && manifest.contains("после сауны"))
        assertTrue(manifest.contains("\"canonicalPayload\""))
        assertTrue(!manifest.contains("pending-measurement") && !manifest.contains("effectiveWaistHipRatio"))
        assertTrue(File(dir, hash).readBytes().contentEquals(bytes))
    }

    @Test fun `manifest remains valid JSON for unicode controls and marks missing snapshots unverifiable`() = runTest {
        val report = HealthReportEntity("r\n✓", 1, 1, false, "CONFIRMED", "LAB", 5, "CBC\tконтроль")
        db.healthDao().upsertReport(report)
        val output = ByteArrayOutputStream()

        exporter().export(output, HealthArchiveSelection(reportIds = setOf(report.syncId)))

        val manifest = unzip(output.toByteArray()).getValue("manifest.json").decodeToString()
        val parsed = Json.parseToJsonElement(manifest).toString()
        assertTrue(parsed.contains("unverifiable:report"))
        assertTrue(manifest.contains("r\\n"))
    }

    @Test fun `partial measurement group export excludes deselected primary values and canonical payload`() = runTest {
        val measurement = BodyMeasurementEntity("m", 100, weightKg = 70.0, waistCm = 80.0, afterMeal = true)
        MeasurementRepository(db).save(measurement, now = 100)
        val output = ByteArrayOutputStream()

        exporter().export(
            output,
            HealthArchiveSelection(measurementIds = setOf("m"), measurementGroups = setOf(MeasurementArchiveGroup.CONDITIONS)),
        )

        val manifest = unzip(output.toByteArray()).getValue("manifest.json").decodeToString()
        assertTrue(manifest.contains("afterMeal"))
        assertTrue(!manifest.contains("weightKg") && !manifest.contains("waistCm") && !manifest.contains("canonicalPayload"))
    }

    @Test fun `partial report exports complete selected observation metadata without full snapshot leak`() = runTest {
        val report = HealthReportEntity("report", 2, 20, false, "CORRECTED", "DOCUMENT", 10, "CBC", "контроль", 1)
        val selected = HealthObservationEntity("kept", "report", 3, 21, false, 11, "Hb", "NUMBER", "120", "g/L", "110-160", "метод", "кровь", "LAB", "hb", 2)
        val hidden = HealthObservationEntity("hidden", "report", 4, 22, false, 12, "WBC", "NUMBER", "7.1", "10^9/L")
        db.healthDao().upsertReport(report)
        db.healthDao().insertObservations(listOf(selected, hidden))
        val full = HealthSyncPayloadCodec.report(report, listOf(selected, hidden))
        db.healthDao().insertReportSnapshot(HealthReportSnapshotEntity("report", 2, 20, false, full, digest(full.encodeToByteArray())))

        val output = ByteArrayOutputStream()
        exporter().export(output, HealthArchiveSelection(reportIds = setOf("report"), observationIds = setOf("kept")))

        val reportJson = Json.parseToJsonElement(unzip(output.toByteArray()).getValue("manifest.json").decodeToString())
            .jsonObject["reports"]!!.jsonArray.single().jsonObject
        assertTrue("partial data has no dictionary material", "hash" !in reportJson && "canonicalPayload" !in reportJson && "verifiable" !in reportJson)
        val filtered = reportJson["exportPayload"]!!.jsonObject
        assertEquals("CORRECTED", filtered["status"]!!.toString().trim('"'))
        assertEquals("DOCUMENT", filtered["provenance"]!!.toString().trim('"'))
        assertEquals(1, filtered["observations"]!!.jsonArray.size)
        assertTrue(filtered.toString().contains("sourcePage"))
        assertTrue(!filtered.toString().contains("WBC") && !filtered.toString().contains("7.1"))
    }

    @Test fun `selected pending original is omitted from zip and reported with owner and state`() = runTest {
        val report = HealthReportEntity("report", 1, 1, false, "CONFIRMED", "DOCUMENT", 1, "CBC")
        db.healthDao().upsertReport(report)
        db.healthDao().upsertDocument(HealthDocumentEntity("pending", "report", "hash", "PENDING", "scan.pdf", "application/pdf", 1, null, 1))
        val selection = HealthArchiveSelection(reportIds = setOf("report"), documentIds = setOf("pending"))

        val preview = exporter().preview(selection)
        val output = ByteArrayOutputStream()
        val result = exporter().export(output, selection) as HealthArchiveExportResult.Success

        assertEquals("Исследование report", preview.missingOriginals.single().owner)
        assertEquals("scan.pdf", preview.missingOriginals.single().displayName)
        assertEquals("ещё не готов", preview.missingOriginals.single().status)
        assertEquals(listOf("health:pending:pending"), result.missingOriginals)
        assertTrue(unzip(output.toByteArray()).keys.none { it.contains("pending") })
    }

    @Test fun `crafted partial metric selection cannot smuggle a linked original`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val measurement = BodyMeasurementEntity("m", 10, weightKg = 70.0, afterMeal = true)
        MeasurementRepository(db).save(measurement, now = 10)
        val bytes = "all-values-photo".encodeToByteArray(); val hash = digest(bytes)
        File(context.noBackupFilesDir, "measurement_documents").apply { mkdirs() }.resolve(hash).writeBytes(bytes)
        db.measurementDocumentDao().upsert(MeasurementDocumentEntity("photo", "m", hash, "READY", "same.jpg", "image/jpeg", bytes.size.toLong(), 1))
        val partial = ByteArrayOutputStream()

        val partialResult = exporter(context).export(
            partial,
            HealthArchiveSelection(
                measurementIds = setOf("m"),
                measurementGroups = setOf(MeasurementArchiveGroup.CONDITIONS),
                documentIds = setOf("photo"),
            ),
        ) as HealthArchiveExportResult.Success

        assertEquals(listOf("measurement:photo:requires-all-groups"), partialResult.missingOriginals)
        assertTrue(unzip(partial.toByteArray()).keys.none { it.startsWith("measurement-documents/") })
        val partialManifest = unzip(partial.toByteArray()).getValue("manifest.json").decodeToString()
        assertTrue(!partialManifest.contains("same.jpg"))

        val full = ByteArrayOutputStream()
        exporter(context).export(full, HealthArchiveSelection(measurementIds = setOf("m"), documentIds = setOf("photo")))
        assertTrue(unzip(full.toByteArray()).keys.any { it.startsWith("measurement-documents/photo-") })
    }

    @Test fun `crafted partial report selection cannot include its original`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val report = HealthReportEntity("report", 1, 1, false, "CONFIRMED", "DOCUMENT", 1, "CBC")
        val kept = HealthObservationEntity("kept", "report", 1, 1, false, 1, "Hb", "NUMBER", "120")
        val omitted = HealthObservationEntity("omitted", "report", 1, 1, false, 1, "WBC", "NUMBER", "7")
        db.healthDao().upsertReport(report); db.healthDao().insertObservations(listOf(kept, omitted))
        val bytes = "original-with-all-results".encodeToByteArray(); val hash = digest(bytes)
        File(context.noBackupFilesDir, "health_documents").apply { mkdirs() }.resolve(hash).writeBytes(bytes)
        db.healthDao().upsertDocument(HealthDocumentEntity("scan", "report", hash, "READY", "scan.pdf", "application/pdf", bytes.size.toLong(), null, 1))
        val output = ByteArrayOutputStream()

        val result = exporter(context).export(output, HealthArchiveSelection(reportIds = setOf("report"), observationIds = setOf("kept"), documentIds = setOf("scan"))) as HealthArchiveExportResult.Success

        assertEquals(listOf("health:scan:requires-all-results"), result.missingOriginals)
        val entries = unzip(output.toByteArray())
        assertTrue(entries.keys.none { it.startsWith("health-documents/") })
        val manifest = entries.getValue("manifest.json").decodeToString()
        assertTrue(!manifest.contains("scan.pdf") && !manifest.contains("WBC") && !manifest.contains("\"hash\""))
    }

    @Test fun `shared source hash and names receive separate stable document paths`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val first = HealthReportEntity("one", 1, 1, false, "CONFIRMED", "DOCUMENT", 1, "One")
        val second = HealthReportEntity("two", 1, 1, false, "CONFIRMED", "DOCUMENT", 1, "Two")
        db.healthDao().upsertReport(first); db.healthDao().upsertReport(second)
        val bytes = "shared".encodeToByteArray(); val hash = digest(bytes)
        File(context.noBackupFilesDir, "health_documents").apply { mkdirs() }.resolve(hash).writeBytes(bytes)
        db.healthDao().upsertDocument(HealthDocumentEntity("one-doc", "one", hash, "READY", "scan.pdf", "application/pdf", bytes.size.toLong(), null, 1))
        db.healthDao().upsertDocument(HealthDocumentEntity("two-doc", "two", hash, "READY", "scan.pdf", "application/pdf", bytes.size.toLong(), null, 1))
        val output = ByteArrayOutputStream()

        exporter(context).export(output, HealthArchiveSelection(reportIds = setOf("one", "two"), documentIds = setOf("one-doc", "two-doc")))

        val paths = unzip(output.toByteArray()).keys.filter { it.startsWith("health-documents/") }
        assertEquals(listOf("health-documents/one-doc-scan.pdf", "health-documents/two-doc-scan.pdf"), paths)
    }

    @Test fun `period bounds include records exactly at both boundaries`() = runTest {
        db.healthDao().upsertReport(HealthReportEntity("start", 1, 1, false, "CONFIRMED", "LAB", 100, "Start"))
        db.healthDao().upsertReport(HealthReportEntity("end", 1, 1, false, "CONFIRMED", "LAB", 200, "End"))
        db.healthDao().upsertReport(HealthReportEntity("outside", 1, 1, false, "CONFIRMED", "LAB", 201, "Outside"))
        val output = ByteArrayOutputStream()

        exporter().export(output, HealthArchiveSelection(reportIds = setOf("start", "end", "outside"), periodStart = 100, periodEnd = 200))

        val reports = Json.parseToJsonElement(unzip(output.toByteArray()).getValue("manifest.json").decodeToString())
            .jsonObject["reports"]!!.jsonArray.map { it.jsonObject["id"].toString().trim('"') }
        assertEquals(listOf("end", "start"), reports)
    }

    private fun exporter(context: android.content.Context = ApplicationProvider.getApplicationContext()) =
        ZipHealthArchiveExporter(context, db.healthDao(), db.bodyMeasurementDao(), db.measurementDocumentDao(), UnconfinedTestDispatcher())

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = ZipInputStream(bytes.inputStream()).use { zip ->
        buildMap { while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()) } }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
