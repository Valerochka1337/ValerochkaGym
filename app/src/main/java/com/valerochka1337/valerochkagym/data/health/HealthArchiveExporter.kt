package com.valerochka1337.valerochkagym.data.health

import android.content.Context
import com.valerochka1337.valerochkagym.data.db.dao.HealthDao
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.dao.MeasurementDocumentDao
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthDocumentEntity
import com.valerochka1337.valerochkagym.data.db.entity.MeasurementDocumentEntity
import com.valerochka1337.valerochkagym.di.ComputeDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class HealthArchiveSelection(
    val measurementIds: Set<String> = emptySet(),
    val reportIds: Set<String> = emptySet(),
    val restrictionIds: Set<String> = emptySet(),
    /** Empty explicitly means no originals, never the old implicit-all behaviour. */
    val documentIds: Set<String> = emptySet(),
    val measurementGroups: Set<MeasurementArchiveGroup> = MeasurementArchiveGroup.entries.toSet(),
    /** Stable observation IDs selected under the selected reports. */
    val observationIds: Set<String> = emptySet(),
    val periodStart: Long = Long.MIN_VALUE,
    val periodEnd: Long = Long.MAX_VALUE,
)
enum class MeasurementArchiveGroup { BODY_COMPOSITION, SEGMENTAL, CIRCUMFERENCES, CONDITIONS }
data class HealthArchivePreview(
    val measurements: Int,
    val reports: Int,
    val restrictions: Int,
    val readyDocuments: Int,
    val missingOriginals: List<ArchiveMissingOriginal>,
)
/** Human-readable private-file warning; no file path or content is exposed. */
data class ArchiveMissingOriginal(
    val id: String,
    val owner: String,
    val displayName: String,
    val status: String,
) {
    val label: String get() = "$owner · $displayName ($status)"
}
sealed interface HealthArchiveExportResult { data class Success(val records: Int, val missingOriginals: List<String>) : HealthArchiveExportResult; data class Failure(val message: String) : HealthArchiveExportResult }

interface HealthArchiveExporter {
    suspend fun preview(selection: HealthArchiveSelection): HealthArchivePreview
    suspend fun export(output: OutputStream, selection: HealthArchiveSelection): HealthArchiveExportResult
}

@Singleton
class ZipHealthArchiveExporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val healthDao: HealthDao,
    private val bodyMeasurementDao: BodyMeasurementDao,
    private val measurementDocumentDao: MeasurementDocumentDao,
    @param:ComputeDispatcher private val dispatcher: CoroutineDispatcher,
    private val recoveryCoordinator: HealthDocumentRecoveryCoordinator? = null,
) : HealthArchiveExporter {
    override suspend fun preview(selection: HealthArchiveSelection): HealthArchivePreview = withContext(dispatcher) {
        recoveryCoordinator?.recoverOnce()
        val selected = selected(selection)
        val requested = requestedDocuments(selection)
        val eligibility = documentEligibility(selected, selection, requested)
        val missing = requested.health.filterNot { it in eligibility.health && verifiedHealth(it) }
            .map { it.missing("Исследование ${it.reportSyncId}", eligibility.healthReason[it.id]) } +
            requested.measurements.filterNot { it in eligibility.measurements && verifiedMeasurement(it) }
                .map { it.missing("Замер ${it.measurementId}", eligibility.measurementReason[it.id]) }
        HealthArchivePreview(selected.measurements.size, selected.reports.size, selected.restrictions.size, eligibility.health.count(::verifiedHealth) + eligibility.measurements.count(::verifiedMeasurement), missing.sortedBy { it.label })
    }

    override suspend fun export(output: OutputStream, selection: HealthArchiveSelection): HealthArchiveExportResult = withContext(dispatcher) {
        try {
            recoveryCoordinator?.recoverOnce()
            val selected = selected(selection)
            val requested = requestedDocuments(selection)
            val eligibility = documentEligibility(selected, selection, requested)
            val docs = eligibility.health.sortedBy { it.id }
            val measurementDocs = eligibility.measurements.sortedBy { it.id }
            val missing = (requested.health.filterNot { it in docs && verifiedHealth(it) }.map { "health:${it.id}:${eligibility.healthReason[it.id] ?: it.state.lowercase()}" } +
                requested.measurements.filterNot { it in measurementDocs && verifiedMeasurement(it) }.map { "measurement:${it.id}:${eligibility.measurementReason[it.id] ?: it.state.lowercase()}" }).sorted()
            ZipOutputStream(NonClosingOutputStream(output)).use { zip ->
                val paths = docs.filter { it.state == "READY" && verifiedHealth(it) }.associateWith { document ->
                    val path = "health-documents/${document.id}-${safe(document.displayName)}"
                    entry(zip, path) { healthSource(document).inputStream().use { input -> input.copyTo(zip) } }
                    path
                }
                val measurementPaths = measurementDocs.filter { it.state == "READY" && verifiedMeasurement(it) }.associateWith { document ->
                    val path = "measurement-documents/${document.id}-${safe(document.displayName)}"
                    entry(zip, path) { measurementSource(document).inputStream().use { input -> input.copyTo(zip) } }
                    path
                }
                val json = manifest(selected, docs, paths, measurementDocs, measurementPaths, missing, selection)
                entry(zip, "manifest.json") { zip.write(json.encodeToByteArray()) }
            }
            HealthArchiveExportResult.Success(selected.measurements.size + selected.reports.size + selected.restrictions.size, missing)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { HealthArchiveExportResult.Failure("Не удалось создать архив") }
    }

    private suspend fun selected(selection: HealthArchiveSelection): Selected = Selected(
        bodyMeasurementDao.observeAll().first().filter {
            it.id in selection.measurementIds && it.measuredAt in selection.periodStart..selection.periodEnd
        },
        healthDao.observeLiveReports().first().filter { it.status != "REVOKED" && it.reportedAt in selection.periodStart..selection.periodEnd && it.syncId in selection.reportIds },
        healthDao.observeLiveRestrictions().first().filter { it.confirmedAt in selection.periodStart..selection.periodEnd && it.syncId in selection.restrictionIds },
    )
    private data class Selected(val measurements: List<BodyMeasurementEntity>, val reports: List<com.valerochka1337.valerochkagym.data.db.entity.HealthReportEntity>, val restrictions: List<com.valerochka1337.valerochkagym.data.db.entity.HealthRestrictionEntity>)
    private data class RequestedDocuments(val health: List<HealthDocumentEntity>, val measurements: List<MeasurementDocumentEntity>)
    private data class EligibleDocuments(
        val health: List<HealthDocumentEntity>,
        val measurements: List<MeasurementDocumentEntity>,
        val healthReason: Map<String, String>,
        val measurementReason: Map<String, String>,
    )
    private suspend fun requestedDocuments(selection: HealthArchiveSelection): RequestedDocuments {
        val health = (healthDao.documentsInState("READY") + healthDao.documentsInState("PENDING"))
            .filter { it.id in selection.documentIds }
        val measurement = bodyMeasurementDao.observeAll().first().flatMap { measurementDocumentDao.forMeasurement(it.id) }
            .filter { it.id in selection.documentIds }
        return RequestedDocuments(health, measurement)
    }
    private suspend fun documentEligibility(
        selected: Selected,
        selection: HealthArchiveSelection,
        requested: RequestedDocuments,
    ): EligibleDocuments {
        val selectedReports = selected.reports.associateBy { it.syncId }
        val completeReports = selectedReports.keys.filterTo(mutableSetOf()) { reportId ->
            healthDao.observations(reportId).filterNot { it.isTombstone }.all { it.syncId in selection.observationIds }
        }
        val selectedMeasurementIds = selected.measurements.mapTo(mutableSetOf()) { it.id }
        val allMeasurementGroups = selection.measurementGroups == MeasurementArchiveGroup.entries.toSet()
        val healthReason = requested.health.associate { document ->
            document.id to when {
                document.state != "READY" -> "pending"
                document.reportSyncId !in selectedReports -> "owner-not-selected"
                document.reportSyncId !in completeReports -> "requires-all-results"
                else -> "ready"
            }
        }
        val measurementReason = requested.measurements.associate { document ->
            document.id to when {
                document.state != "READY" -> "pending"
                document.measurementId !in selectedMeasurementIds -> "owner-not-selected"
                !allMeasurementGroups -> "requires-all-groups"
                else -> "ready"
            }
        }
        return EligibleDocuments(
            health = requested.health.filter { healthReason[it.id] == "ready" },
            measurements = requested.measurements.filter { measurementReason[it.id] == "ready" },
            healthReason = healthReason,
            measurementReason = measurementReason,
        )
    }
    private suspend fun manifest(selected: Selected, docs: List<HealthDocumentEntity>, paths: Map<HealthDocumentEntity, String>, measurementDocs: List<MeasurementDocumentEntity>, measurementPaths: Map<MeasurementDocumentEntity, String>, missing: List<String>, selection: HealthArchiveSelection): String {
        val warnings = mutableListOf<String>()
        fun verifiable(payload: String?, storedHash: String?, key: String): Boolean =
            (payload != null && storedHash != null && hash(payload) == storedHash).also { if (!it) warnings += "unverifiable:$key" }
        fun document(document: HealthDocumentEntity) = buildJsonObject {
            put("id", document.id); put("path", paths[document]?.let(::JsonPrimitive) ?: JsonNull)
            put("sha256", document.sha256); put("size", document.byteSize); put("displayName", document.displayName); put("mimeType", document.mimeType)
        }
        fun measurementDocument(document: MeasurementDocumentEntity) = buildJsonObject {
            put("id", document.id); put("path", measurementPaths[document]?.let(::JsonPrimitive) ?: JsonNull)
            put("sha256", document.sha256); put("size", document.byteSize); put("displayName", document.displayName); put("mimeType", document.mimeType)
        }
        return buildJsonObject {
            put("schemaVersion", 2); put("exportedAt", 0)
            put("period", buildJsonObject { put("start", selection.periodStart); put("end", selection.periodEnd) })
            put("measurements", buildJsonArray { selected.measurements.sortedBy { it.id }.forEach { measurement ->
                val snapshot = healthDao.measurementSnapshots(measurement.id).maxByOrNull { it.version }
                val complete = selection.measurementGroups == MeasurementArchiveGroup.entries.toSet()
                add(buildJsonObject {
                    put("id", measurement.id); put("measuredAt", measurement.measuredAt); put("version", snapshot?.version ?: 0)
                    val filtered = measurementPayload(measurement, selection.measurementGroups)
                    put("exportPayload", filtered); put("exportHash", hash(filtered.toString()))
                    if (complete) {
                        put("hash", snapshot?.payloadHash?.let(::JsonPrimitive) ?: JsonNull)
                        put("canonicalPayload", snapshot?.canonicalPayload?.let(::JsonPrimitive) ?: JsonNull)
                        put("verifiable", verifiable(snapshot?.canonicalPayload, snapshot?.payloadHash, "measurement:${measurement.id}"))
                    } else {
                        // A partial export must not disclose the complete snapshot fingerprint:
                        // hashes are dictionary material when the omitted primary values are known.
                    }
                    put("documents", buildJsonArray { measurementDocs.filter { it.measurementId == measurement.id && it in measurementPaths }.sortedBy { it.id }.forEach { add(measurementDocument(it)) } })
                })
            } })
            put("reports", buildJsonArray { selected.reports.sortedBy { it.syncId }.forEach { report ->
                val snapshot = healthDao.reportSnapshots(report.syncId).firstOrNull { it.version == report.version }
                val observations = healthDao.observations(report.syncId).filterNot { it.isTombstone }.sortedBy { it.syncId }
                val complete = observations.all { it.syncId in selection.observationIds }
                val filtered = reportPayload(report, observations.filter { it.syncId in selection.observationIds })
                add(buildJsonObject {
                    put("id", report.syncId); put("version", report.version)
                    put("exportPayload", filtered); put("exportHash", hash(filtered.toString()))
                    if (complete) {
                        put("hash", snapshot?.payloadHash?.let(::JsonPrimitive) ?: JsonNull)
                        put("canonicalPayload", snapshot?.canonicalPayload?.let(::JsonPrimitive) ?: JsonNull)
                        put("verifiable", verifiable(snapshot?.canonicalPayload, snapshot?.payloadHash, "report:${report.syncId}"))
                    } else {
                    }
                    put("documents", buildJsonArray { docs.filter { it.reportSyncId == report.syncId && it in paths }.sortedBy { it.id }.forEach { add(document(it)) } })
                })
            } })
            put("restrictions", buildJsonArray { selected.restrictions.sortedBy { it.syncId }.forEach { restriction ->
                val snapshot = healthDao.restrictionSnapshots(restriction.syncId).firstOrNull { it.version == restriction.version }
                add(buildJsonObject {
                    put("id", restriction.syncId); put("version", restriction.version); put("hash", snapshot?.payloadHash?.let(::JsonPrimitive) ?: JsonNull); put("canonicalPayload", snapshot?.canonicalPayload?.let(::JsonPrimitive) ?: JsonNull)
                    put("verifiable", verifiable(snapshot?.canonicalPayload, snapshot?.payloadHash, "restriction:${restriction.syncId}"))
                    put("localOriginalText", snapshot?.originalText?.let(::JsonPrimitive) ?: JsonNull)
                })
            } })
            put("missingOriginals", JsonArray(missing.sorted().map(::JsonPrimitive)))
            put("warnings", JsonArray(warnings.sorted().map(::JsonPrimitive)))
        }.toString()
    }
    private fun healthSource(document: HealthDocumentEntity) = File(context.noBackupFilesDir, "health_documents/${document.sha256}")
    private fun measurementSource(document: MeasurementDocumentEntity) = File(context.noBackupFilesDir, "measurement_documents/${document.sha256}")
    private fun verifiedHealth(document: HealthDocumentEntity): Boolean = healthSource(document).takeIf(File::isFile)?.let { it.length() == document.byteSize && hash(it.readBytes()) == document.sha256 } == true
    private fun verifiedMeasurement(document: MeasurementDocumentEntity): Boolean = measurementSource(document).takeIf(File::isFile)?.let { it.length() == document.byteSize && hash(it.readBytes()) == document.sha256 } == true
    private fun HealthDocumentEntity.missing(owner: String, reason: String?) = ArchiveMissingOriginal("health:$id", owner, displayName, missingStatus(reason))
    private fun MeasurementDocumentEntity.missing(owner: String, reason: String?) = ArchiveMissingOriginal("measurement:$id", owner, displayName, missingStatus(reason))
    private fun missingStatus(reason: String?) = when (reason) {
        "pending" -> "ещё не готов"
        "owner-not-selected" -> "владелец не выбран"
        "requires-all-results", "requires-all-groups" -> "нужны все показатели"
        else -> "повреждён или отсутствует"
    }
    private fun entry(zip: ZipOutputStream, name: String, write: () -> Unit) { zip.putNextEntry(ZipEntry(name).apply { time = 0 }); write(); zip.closeEntry() }
    private fun hash(value: String) = hash(value.encodeToByteArray())
    private fun reportPayload(
        report: com.valerochka1337.valerochkagym.data.db.entity.HealthReportEntity,
        observations: List<com.valerochka1337.valerochkagym.data.db.entity.HealthObservationEntity>,
    ) = buildJsonObject {
        put("id", report.syncId); put("version", report.version); put("updatedAt", report.updatedAt)
        put("isTombstone", report.isTombstone); put("status", report.status); put("provenance", report.provenance)
        put("reportedAt", report.reportedAt); put("title", report.title); put("note", report.note?.let(::JsonPrimitive) ?: JsonNull)
        put("supersedesVersion", report.supersedesVersion?.let(::JsonPrimitive) ?: JsonNull)
        put("observations", buildJsonArray {
            observations.sortedBy { it.syncId }.forEach { observation -> add(buildJsonObject {
                put("id", observation.syncId); put("reportId", observation.reportSyncId); put("version", observation.version)
                put("updatedAt", observation.updatedAt); put("isTombstone", observation.isTombstone); put("observedAt", observation.observedAt)
                put("rawName", observation.rawName); put("valueType", observation.valueType); put("rawValue", observation.rawValue)
                put("unit", observation.unit?.let(::JsonPrimitive) ?: JsonNull); put("reference", observation.referenceRange?.let(::JsonPrimitive) ?: JsonNull)
                put("method", observation.method?.let(::JsonPrimitive) ?: JsonNull); put("material", observation.material?.let(::JsonPrimitive) ?: JsonNull)
                put("source", observation.source?.let(::JsonPrimitive) ?: JsonNull); put("canonicalKey", observation.canonicalKey?.let(::JsonPrimitive) ?: JsonNull)
                put("sourcePage", observation.sourcePage?.let(::JsonPrimitive) ?: JsonNull)
            }) }
        })
    }
    /** Filtered archive representation never serializes a deselected primary field. */
    private fun measurementPayload(
        measurement: BodyMeasurementEntity,
        groups: Set<MeasurementArchiveGroup>,
    ) = buildJsonObject {
        put("id", measurement.id); put("measuredAt", measurement.measuredAt)
        fun nullable(name: String, value: Double?) { value?.let { put(name, it) } }
        fun nullableInt(name: String, value: Int?) { value?.let { put(name, it) } }
        if (MeasurementArchiveGroup.BODY_COMPOSITION in groups) {
            nullable("weightKg", measurement.weightKg); nullable("skeletalMuscleMassKg", measurement.skeletalMuscleMassKg)
            nullable("bodyFatPercentage", measurement.bodyFatPercentage); nullable("bodyFatMassKg", measurement.bodyFatMassKg)
            nullableInt("visceralFatLevel", measurement.visceralFatLevel); nullable("waistHipRatio", measurement.waistHipRatio)
            nullableInt("inBodyScore", measurement.inBodyScore); nullable("totalBodyWaterLiters", measurement.totalBodyWaterLiters)
            nullable("proteinKg", measurement.proteinKg); nullable("mineralsKg", measurement.mineralsKg); nullable("bodyMassIndex", measurement.bodyMassIndex)
            nullable("fatFreeMassKg", measurement.fatFreeMassKg); nullableInt("basalMetabolicRateKcal", measurement.basalMetabolicRateKcal); nullableInt("recommendedCalorieIntakeKcal", measurement.recommendedCalorieIntakeKcal)
        }
        if (MeasurementArchiveGroup.SEGMENTAL in groups) {
            nullable("leftArmLeanMassKg", measurement.leftArmLeanMassKg); nullable("leftArmLeanPercentage", measurement.leftArmLeanPercentage); nullable("leftArmFatMassKg", measurement.leftArmFatMassKg); nullable("leftArmFatPercentage", measurement.leftArmFatPercentage)
            nullable("rightArmLeanMassKg", measurement.rightArmLeanMassKg); nullable("rightArmLeanPercentage", measurement.rightArmLeanPercentage); nullable("rightArmFatMassKg", measurement.rightArmFatMassKg); nullable("rightArmFatPercentage", measurement.rightArmFatPercentage)
            nullable("trunkLeanMassKg", measurement.trunkLeanMassKg); nullable("trunkLeanPercentage", measurement.trunkLeanPercentage); nullable("trunkFatMassKg", measurement.trunkFatMassKg); nullable("trunkFatPercentage", measurement.trunkFatPercentage)
            nullable("leftLegLeanMassKg", measurement.leftLegLeanMassKg); nullable("leftLegLeanPercentage", measurement.leftLegLeanPercentage); nullable("leftLegFatMassKg", measurement.leftLegFatMassKg); nullable("leftLegFatPercentage", measurement.leftLegFatPercentage)
            nullable("rightLegLeanMassKg", measurement.rightLegLeanMassKg); nullable("rightLegLeanPercentage", measurement.rightLegLeanPercentage); nullable("rightLegFatMassKg", measurement.rightLegFatMassKg); nullable("rightLegFatPercentage", measurement.rightLegFatPercentage)
        }
        if (MeasurementArchiveGroup.CIRCUMFERENCES in groups) {
            nullable("waistCm", measurement.waistCm); nullable("chestCm", measurement.chestCm); nullable("hipsCm", measurement.hipsCm); nullable("rightRelaxedArmCm", measurement.rightRelaxedArmCm); nullable("rightThighCm", measurement.rightThighCm)
        }
        if (MeasurementArchiveGroup.CONDITIONS in groups) {
            put("afterMeal", measurement.afterMeal); put("afterWorkout", measurement.afterWorkout); put("unusualHydration", measurement.unusualHydration); put("conditionNote", measurement.conditionNote?.let(::JsonPrimitive) ?: JsonNull)
        }
    }
    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "original" }
}

/** ZipOutputStream finishes its central directory without taking ownership of a caller SAF stream. */
private class NonClosingOutputStream(private val delegate: OutputStream) : OutputStream() {
    override fun write(b: Int) = delegate.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
    override fun flush() = delegate.flush()
    override fun close() = flush()
}
