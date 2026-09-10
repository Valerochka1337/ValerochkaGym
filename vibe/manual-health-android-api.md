# Manual health — Android implementation boundary

This appendix implements the accepted `manual-health-plan.md`; the canonical
`contracts/manual-health-contract.json` remains authoritative. It adds no feature scope.
Implementation starts only after Profile acceptance. `shared_owner` owns domain/data,
Room/migrations/schema, sync/ownership, consent storage, navigation, DI and version.
The UI writer owns Health screens/ViewModels and their tests. Root alone runs Gradle.

## Typed data

All identifiers are Strings, epochs/revisions Long, nullable where not assigned yet.
Enums: HealthRecordKind REPORT/OBSERVATION/RESTRICTION; HealthVersionState
CONFIRMED/TOMBSTONE; HealthObservedPrecision DATE/DATETIME; HealthValueKind
NUMBER/COMPARATOR/RANGE/CATEGORY/TEXT; HealthOperator LT/LE/GT/GE/EQ.

`HealthEditorDraft` is a sealed union:

- Report: title:String, sourceText:String?, observedAt:String, observedPrecision.
- Observation: reportLogicalId:String, metricIdentityId:String,
  metricNameOriginal:String, valueKind, valueOriginal:String, numberValue:String?,
  rangeLow:String?, rangeHigh:String?, operator:HealthOperator?, unitOriginal:String?,
  methodOriginal:String?, specimenOriginal:String?, sourceOriginal:String?,
  referenceOriginal:String?, observedAt:String, observedPrecision.
- Restriction: textOriginal:String.

The immutable `HealthPayload` union retains those fields and adds
enteredAtEpochMs:Long to Observation and confirmedAtEpochMs:Long to Restriction.
Repository WallClock stamps confirmation/version time; UI does not backdate audit time.
DATE uses canonical LocalDate; DATETIME preserves the exact RFC3339 offset/text
(seconds required), matching backend validation. Nullable decimal strings follow the
fixture regex (18 integral / 12 fractional digits); originals stay intact. Health text
bounds count UTF-16 units, unlike the earlier notes/profile code-point limit.

`HealthEditTarget(scope, ownerId?, sessionEpoch, logicalId?, currentVersionId?,
baseHeadRevision)` binds every write to its opening context.
`HealthEditorSnapshot(target, draft)` supplies the editor.

`HealthCurrentRecord(logicalId, kind, createdAtEpochMs, currentVersionId?, headRevision,
deleted, healthRevision?, payload?)` supplies readable report titles and observation
values; tombstones have no payload. `HealthVersionSnapshot(versionId, logicalId,
parentVersionId?, kind, state, enteredAtEpochMs, payload?, serverSequence?, healthRevision?)`
retains every immutable audit version. `HealthHistory` exposes current, versions and
headHistory. `HealthHeadHistorySnapshot` carries logicalId, headRevision, currentVersionId,
kind, deleted and healthRevision for the existing required head-only audit events.

`HealthMetricIdentity(id, nameOriginal, createdAtEpochMs)` is explicitly selected/created;
names never merge identities. `HealthTrendKey` is metricIdentityId plus exact nullable
unit/method/specimen. Numeric points retain decimal strings; incompatible values have
an explicit reason/table. No unit conversion or clinical interpretation is introduced.

## Repository seam

`domain/HealthRepository.kt` exposes:

- observeCurrent(): Flow<List<HealthCurrentRecord>>
- observeHistory(logicalId): Flow<HealthHistory?>
- observeMetrics(): Flow<List<HealthMetricIdentity>>
- observeAnalysis(): Flow<HealthAnalysisSnapshot>
- observeBodyMeasurementReferences(ids:Set<String>): Flow<List<BodyMeasurementEntity>>
- suspend openCreate(kind): HealthEditorSnapshot?
- suspend openEdit(logicalId): HealthEditorSnapshot?
- suspend createMetric(target, nameOriginal): HealthMetricMutationResult
- suspend confirm(target, draft): HealthMutationResult
- suspend tombstone(target): HealthMutationResult

Mutation results: Saved(logicalId, versionId), StorageAcknowledgementRequired, Invalid,
StaleTarget, MissingOrDeleted. Metric results: Created(identity), Invalid, StaleTarget.
Analysis snapshot includes currentRecords, trends and distinct bodyMeasurementIds.
Reference flow reads existing BodyMeasurement DAO rows only; values never enter health
entities, portable records, baseline, cursor or outbox. No heuristic matching is allowed.

## Consent and UI integration

The data owner implements HealthConsentStore and its two new SettingsRepository keys:
local notice acknowledgement version and backend-health-sync choice. Its immutable
snapshot exposes localStorageAcknowledgedVersion, backendSyncEnabled and existing
aiDisclosureEnabled. Methods acknowledgeCurrentStorageNotice() and
setBackendSyncEnabled(Boolean) do not mutate AI disclosure.

Existing HealthAiDisclosureRepository remains the only AI consent mutation path and
reuses existing Room receipt/intent/exact outbox. No second aggregate is created.

Health is the fourth Analysis section after Progress. Shared navigation owns pushed
detail/editor routes and existing measurement-reference navigation. UI covers manual
entry, correction, tombstone/history, explicit metric selection and distinct consent
controls. Read history never requires a renewed storage acknowledgement. No new AI
entry, import, attachment, clinical advice or restore-old-head action belongs here.
