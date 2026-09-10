package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import kotlinx.coroutines.flow.Flow

enum class HealthRecordKind {
  REPORT,
  OBSERVATION,
  RESTRICTION,
}

enum class HealthVersionState {
  CONFIRMED,
  TOMBSTONE,
}

enum class HealthObservedPrecision {
  DATE,
  DATETIME,
}

enum class HealthValueKind {
  NUMBER,
  COMPARATOR,
  RANGE,
  CATEGORY,
  TEXT,
}

enum class HealthOperator {
  LT,
  LE,
  GT,
  GE,
  EQ,
}

sealed interface HealthEditorDraft {
  data class Report(
      val title: String,
      val sourceText: String?,
      val observedAt: String,
      val observedPrecision: HealthObservedPrecision,
  ) : HealthEditorDraft

  data class Observation(
      val reportLogicalId: String,
      val metricIdentityId: String,
      val metricNameOriginal: String,
      val valueKind: HealthValueKind,
      val valueOriginal: String,
      val numberValue: String?,
      val rangeLow: String?,
      val rangeHigh: String?,
      val operator: HealthOperator?,
      val unitOriginal: String?,
      val methodOriginal: String?,
      val specimenOriginal: String?,
      val sourceOriginal: String?,
      val referenceOriginal: String?,
      val observedAt: String,
      val observedPrecision: HealthObservedPrecision,
  ) : HealthEditorDraft

  data class Restriction(val textOriginal: String) : HealthEditorDraft
}

sealed interface HealthPayload {
  data class Report(
      val title: String,
      val sourceText: String?,
      val observedAt: String,
      val observedPrecision: HealthObservedPrecision,
  ) : HealthPayload

  data class Observation(
      val reportLogicalId: String,
      val metricIdentityId: String,
      val metricNameOriginal: String,
      val valueKind: HealthValueKind,
      val valueOriginal: String,
      val numberValue: String?,
      val rangeLow: String?,
      val rangeHigh: String?,
      val operator: HealthOperator?,
      val unitOriginal: String?,
      val methodOriginal: String?,
      val specimenOriginal: String?,
      val sourceOriginal: String?,
      val referenceOriginal: String?,
      val observedAt: String,
      val observedPrecision: HealthObservedPrecision,
      val enteredAtEpochMs: Long,
  ) : HealthPayload

  data class Restriction(val textOriginal: String, val confirmedAtEpochMs: Long) : HealthPayload
}

data class HealthEditTarget(
    val scope: String,
    val ownerId: String?,
    val sessionEpoch: Long,
    val logicalId: String?,
    val currentVersionId: String?,
    val baseHeadRevision: Long,
)

data class HealthEditorSnapshot(val target: HealthEditTarget, val draft: HealthEditorDraft)

data class HealthCurrentRecord(
    val logicalId: String,
    val kind: HealthRecordKind,
    val createdAtEpochMs: Long,
    val currentVersionId: String?,
    val headRevision: Long,
    val deleted: Boolean,
    val healthRevision: Long?,
    val payload: HealthPayload?,
)

data class HealthVersionSnapshot(
    val versionId: String,
    val logicalId: String,
    val parentVersionId: String?,
    val kind: HealthRecordKind,
    val state: HealthVersionState,
    val enteredAtEpochMs: Long,
    val payload: HealthPayload?,
    val serverSequence: Long?,
    val healthRevision: Long?,
)

data class HealthHeadHistorySnapshot(
    val logicalId: String,
    val headRevision: Long,
    val currentVersionId: String,
    val kind: HealthRecordKind,
    val deleted: Boolean,
    val healthRevision: Long,
)

data class HealthHistory(
    val current: HealthCurrentRecord,
    val versions: List<HealthVersionSnapshot>,
    val headHistory: List<HealthHeadHistorySnapshot>,
)

data class HealthMetricIdentity(
    val id: String,
    val nameOriginal: String,
    val createdAtEpochMs: Long,
)

data class HealthTrendKey(
    val metricIdentityId: String,
    val unitOriginal: String?,
    val methodOriginal: String?,
    val specimenOriginal: String?,
)

data class HealthNumericPoint(val logicalId: String, val observedAt: String, val value: String)

enum class HealthTrendIncompatibilityReason {
  TOO_FEW_POINTS,
  VALUE_KIND,
  METRIC,
  UNIT,
  METHOD,
  SPECIMEN,
}

data class HealthTrendIncompatibility(
    val logicalId: String,
    val reason: HealthTrendIncompatibilityReason,
    val valueOriginal: String,
)

data class HealthTrend(
    val key: HealthTrendKey,
    val points: List<HealthNumericPoint>,
    val incompatible: List<HealthTrendIncompatibility>,
)

data class HealthAnalysisSnapshot(
    val currentRecords: List<HealthCurrentRecord>,
    val trends: List<HealthTrend>,
    val bodyMeasurementIds: List<String>,
)

sealed interface HealthMutationResult {
  data class Saved(val logicalId: String, val versionId: String) : HealthMutationResult

  data object StorageAcknowledgementRequired : HealthMutationResult

  data object Invalid : HealthMutationResult

  data object StaleTarget : HealthMutationResult

  data object MissingOrDeleted : HealthMutationResult
}

sealed interface HealthMetricMutationResult {
  data class Created(val identity: HealthMetricIdentity) : HealthMetricMutationResult

  data object Invalid : HealthMetricMutationResult

  data object StaleTarget : HealthMetricMutationResult
}

interface HealthRepository {
  fun observeCurrent(): Flow<List<HealthCurrentRecord>>

  fun observeHistory(logicalId: String): Flow<HealthHistory?>

  fun observeMetrics(): Flow<List<HealthMetricIdentity>>

  fun observeAnalysis(): Flow<HealthAnalysisSnapshot>

  fun observeBodyMeasurementReferences(ids: Set<String>): Flow<List<BodyMeasurementEntity>>

  /** Emits false immediately after the captured account/cache scope is no longer current. */
  fun observeTargetValidity(target: HealthEditTarget): Flow<Boolean>

  suspend fun openCreate(kind: HealthRecordKind): HealthEditorSnapshot?

  suspend fun openEdit(logicalId: String): HealthEditorSnapshot?

  suspend fun createMetric(
      target: HealthEditTarget,
      nameOriginal: String,
  ): HealthMetricMutationResult

  suspend fun confirm(target: HealthEditTarget, draft: HealthEditorDraft): HealthMutationResult

  suspend fun tombstone(target: HealthEditTarget): HealthMutationResult
}
