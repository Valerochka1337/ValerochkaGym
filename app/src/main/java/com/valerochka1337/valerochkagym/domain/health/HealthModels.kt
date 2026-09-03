package com.valerochka1337.valerochkagym.domain.health

import java.util.UUID

enum class HealthRawValueKind { NUMBER, NUMBER_WITH_OPERATOR, RANGE, CATEGORY, CODE, TEXT }
enum class HealthReportStatus { CONFIRMED, CORRECTED, REVOKED }
enum class HealthRestrictionState { ACTIVE, TEMPORARY, LIFTED }
enum class HealthInformationSource { USER, CLINICIAN, DOCUMENT }

sealed interface HealthRawValue {
    val kind: HealthRawValueKind
    val raw: String

    data class Number(val value: Double, override val raw: String = value.toString()) : HealthRawValue {
        override val kind = HealthRawValueKind.NUMBER
    }
    data class NumberWithOperator(val operator: String, val value: Double) : HealthRawValue {
        override val kind = HealthRawValueKind.NUMBER_WITH_OPERATOR
        override val raw = "$operator$value"
    }
    data class Range(val lower: Double?, val upper: Double?, override val raw: String) : HealthRawValue {
        override val kind = HealthRawValueKind.RANGE
    }
    data class Category(override val raw: String) : HealthRawValue { override val kind = HealthRawValueKind.CATEGORY }
    data class Code(override val raw: String) : HealthRawValue { override val kind = HealthRawValueKind.CODE }
    data class Text(override val raw: String) : HealthRawValue { override val kind = HealthRawValueKind.TEXT }
}

data class HealthObservationDraft(
    val rawName: String,
    val value: HealthRawValue,
    val unit: String? = null,
    val referenceRange: String? = null,
    val method: String? = null,
    val material: String? = null,
    val source: String? = null,
    /** Null deliberately means an unknown name that cannot merge with a similar spelling. */
    val canonicalKey: String? = null,
    val observedAt: Long,
    /** One-based document page, retained as primary result provenance. */
    val sourcePage: Int? = null,
)

data class HealthReportDraft(
    val title: String,
    val provenance: String,
    val reportedAt: Long,
    val observations: List<HealthObservationDraft>,
    val note: String? = null,
)

data class ConfirmedHealthReport(
    val syncId: String,
    val version: Long,
    val updatedAt: Long,
    val status: HealthReportStatus,
    val supersedesVersion: Long?,
    val draft: HealthReportDraft,
)

data class HealthRestrictionDraft(
    val description: String,
    val state: HealthRestrictionState,
    val source: HealthInformationSource,
    val confirmedAt: Long,
    val startsAt: Long? = null,
    val reviewAt: Long? = null,
)

data class ConfirmedHealthRestriction(
    val syncId: String = UUID.randomUUID().toString(),
    val version: Long = 1,
    val updatedAt: Long,
    val draft: HealthRestrictionDraft,
)

data class HealthTrendPoint(val observedAt: Long, val value: Double, val referenceRange: String?)
data class HealthTrendSeries(
    val canonicalKey: String,
    val unit: String?,
    val material: String?,
    val method: String?,
    val source: String?,
    val points: List<HealthTrendPoint>,
)
