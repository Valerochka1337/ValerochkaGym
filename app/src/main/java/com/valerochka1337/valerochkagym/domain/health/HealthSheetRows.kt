package com.valerochka1337.valerochkagym.domain.health

import com.valerochka1337.valerochkagym.data.db.entity.HealthObservationEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRestrictionEntity

/** Stable, primary-data-only Sheets representation. Documents, drafts and derived values have no row. */
object HealthSheetRows {
    /** Managed Sheets v10 contract. B1 consumes these constants verbatim. */
    const val REPORT_SHEET = "HealthReports"
    const val OBSERVATION_SHEET = "HealthObservations"
    const val RESTRICTION_SHEET = "HealthRestrictions"
    const val MANAGED_LAST_COLUMN = "AY"
    val REPORT_HEADER = listOf(
        "report_id", "version", "updated_at", "is_deleted", "status", "provenance", "reported_at", "collected_at",
        "title", "note", "supersedes_version", "correction_of_version", "conditions", "original_expected", "payload_hash", "idempotency_key",
    )
    val OBSERVATION_HEADER = listOf("observation_id", "report_id", "report_version", "version", "updated_at", "is_deleted", "observed_at", "raw_name", "value_type", "raw_value", "unit", "reference_range", "method", "material", "source", "canonical_key", "source_page")
    /** Transitional v10 wire: aggregate revision but no page-level provenance. */
    val REPORT_VERSION_OBSERVATION_HEADER = OBSERVATION_HEADER.dropLast(1)
    /** Previous health wire has no aggregate revision; it is read only and unambiguous v1 only. */
    val LEGACY_OBSERVATION_HEADER = REPORT_VERSION_OBSERVATION_HEADER.filterIndexed { index, _ -> index != 2 }
    val RESTRICTION_HEADER = listOf("restriction_id", "version", "updated_at", "is_deleted", "status", "source", "confirmed_at", "starts_at", "review_at", "description", "payload_hash", "idempotency_key")

    fun reportRow(report: HealthReportEntity, hash: String?, idempotencyKey: String): List<String> = listOf(
        report.syncId, report.version.toString(), report.updatedAt.toString(), report.isTombstone.bit(), report.status,
        report.provenance, report.reportedAt.toString(), report.collectedAt?.toString().orEmpty(), report.title, report.note.orEmpty(),
        report.supersedesVersion?.toString().orEmpty(), report.correctionOfVersion?.toString().orEmpty(), report.conditions.orEmpty(),
        report.originalExpected.bit(), hash.orEmpty(), idempotencyKey,
    )

    fun observationRow(observation: HealthObservationEntity, reportVersion: Long): List<String> = listOf(
        observation.syncId, observation.reportSyncId, reportVersion.toString(), observation.version.toString(), observation.updatedAt.toString(),
        observation.isTombstone.bit(), observation.observedAt.toString(), observation.rawName, observation.valueType,
        observation.rawValue, observation.unit.orEmpty(), observation.referenceRange.orEmpty(), observation.method.orEmpty(),
        observation.material.orEmpty(), observation.source.orEmpty(), observation.canonicalKey.orEmpty(), observation.sourcePage?.toString().orEmpty(),
    )

    fun restrictionRow(restriction: HealthRestrictionEntity, hash: String?, idempotencyKey: String): List<String> = listOf(
        restriction.syncId, restriction.version.toString(), restriction.updatedAt.toString(), restriction.isTombstone.bit(),
        restriction.status, restriction.source, restriction.confirmedAt.toString(), restriction.startsAt?.toString().orEmpty(),
        restriction.reviewAt?.toString().orEmpty(), restriction.description, hash.orEmpty(), idempotencyKey,
    )

    private fun Boolean.bit() = if (this) "1" else "0"
}
