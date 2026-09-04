package com.valerochka1337.valerochkagym.domain.health

import java.util.UUID

enum class HealthRawValueKind { NUMBER, NUMBER_WITH_OPERATOR, RANGE, CATEGORY, CODE, TEXT }
/** Final v10 lifecycle. `CONFIRMED` existed only in an unreleased checkpoint wire. */
enum class HealthReportStatus { PRELIMINARY, FINAL, CORRECTED, REVOKED }
enum class HealthRestrictionState { ACTIVE, TEMPORARY, LIFTED }
enum class HealthInformationSource { USER, CLINICIAN, DOCUMENT }

sealed interface HealthRawValue {
    val kind: HealthRawValueKind
    val raw: String

    data class Number(val value: Double, override val raw: String = value.toString()) : HealthRawValue {
        override val kind = HealthRawValueKind.NUMBER
    }
    /**
     * [raw] is deliberately not reconstructed from [value]: `>=02.00` and `>=2.0` have the
     * same numeric component but are different primary source text.
     */
    data class NumberWithOperator(
        val operator: String,
        val value: Double,
        override val raw: String = "$operator$value",
    ) : HealthRawValue {
        override val kind = HealthRawValueKind.NUMBER_WITH_OPERATOR
        init {
            require(operator in setOf("<", "<=", ">", ">="))
            require(value.isFinite())
            require(raw.startsWith(operator))
            require(raw.removePrefix(operator).toDoubleOrNull()?.isFinite() == true)
        }
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
    /** A suggestion (including one from AI) is never persisted until this explicit confirmation. */
    val canonicalKeyAccepted: Boolean = false,
    /** Stable identity is supplied by correctionDraft for logically unchanged observations. */
    val observationSyncId: String? = null,
)

data class HealthReportDraft(
    val title: String,
    val provenance: String,
    val reportedAt: Long,
    val observations: List<HealthObservationDraft>,
    val note: String? = null,
    val collectedAt: Long? = null,
    val conditions: String? = null,
    /** Whether the user expects a private original, not a claim that bytes currently exist. */
    val originalExpected: Boolean = false,
)

data class ConfirmedHealthReport(
    val syncId: String,
    val version: Long,
    val updatedAt: Long,
    val status: HealthReportStatus,
    val supersedesVersion: Long?,
    val draft: HealthReportDraft,
    val correctionOfVersion: Long? = null,
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

/** Stable command identity makes a lost response safe to retry without another revision. */
data class ReportSaveCommand(
    val operationId: String,
    val reportSyncId: String,
    val draft: HealthReportDraft,
    val status: HealthReportStatus = HealthReportStatus.FINAL,
    val correctionOfVersion: Long? = null,
)

sealed interface ReportSaveResult {
    val report: ConfirmedHealthReport
    data class Saved(override val report: ConfirmedHealthReport) : ReportSaveResult
    data class Replayed(override val report: ConfirmedHealthReport) : ReportSaveResult
}

data class HealthRestrictionProposal(
    val proposalId: String,
    val description: String,
    val state: HealthRestrictionState,
    val source: HealthInformationSource,
    val startsAt: Long? = null,
    val reviewAt: Long? = null,
)

data class ConfirmRestrictionProposalsCommand(
    val operationId: String,
    val proposals: List<HealthRestrictionProposal>,
    val originalText: String? = null,
)

sealed interface RestrictionConfirmationResult {
    data class Confirmed(val restrictions: List<ConfirmedHealthRestriction>) : RestrictionConfirmationResult
    data class Replayed(val restrictions: List<ConfirmedHealthRestriction>) : RestrictionConfirmationResult
}
