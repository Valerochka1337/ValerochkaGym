# Health data — implementation tracker

Plan: `vibe/health-data-plan.md`. Status vocabulary: `pending | in_progress | done | blocked`.

## Tasks

| Task | Status | Owner | Dependencies | Done evidence |
|---|---|---|---|---|
| T-001 Persistence contract | in_progress | Implementation writer | — | Working checkpoint: v10 migration/backfill and targeted evidence exist; W1 must revalidate immutable v1/v2 compatibility before AC acceptance |
| T-002 Domain/repositories | in_progress | Implementation writer | T-001 | Working checkpoint: typed values, transactions and history exist; W1/W2 retain final persistence/conflict acceptance |
| T-003 Measurements versioning | in_progress | Implementation writer | T-001, T-002 | Working checkpoint: sole mutation boundary and current A:AY (not stale A:AU) header/parser/import/tombstone paths exist; W1–W3 retain legacy/semantic Sheets acceptance |
| T-004 Health Sheets + sync settings | in_progress | Implementation writer | T-002, T-003 | Working checkpoint: ACK/preflight/settings/clear transport exists; W2/W3 retain delivery and category-isolation acceptance |
| T-005 Reader and private originals | in_progress | Implementation writer | T-001, T-002 | Working checkpoint: strict reader and PENDING→READY targeted coverage exists; W4 retains privacy/recovery/cancellation acceptance |
| T-006 Health UI and navigation | in_progress | Implementation writer | T-002, T-003, T-005 | Working checkpoint: routes/detail/editor/Analysis state exist; W5 retains UI/accessibility/manual acceptance |
| T-007 Archive/SAF integration | in_progress | Implementation writer | T-002, T-006 | Working checkpoint: deterministic SAF/READY manifest and route exist; W5 retains archive/SAF acceptance |
| T-008 Integration hardening | pending | Implementation writer | W1…W5 | W6 only: final unit + debug/release assemble, evidence reconciliation, then separately authorized version increment |

## AC → task → verification traceability

| AC | Tasks | Verification |
|---|---|---|
| AC-001–003 | T-002,T-006 | repository draft/cancel + UI state tests |
| AC-004–005 | T-001,T-002,T-005 | typed-value and strict-reader tests |
| AC-006–008 | T-002,T-006 | trend/comparability and accessible-table tests |
| AC-009–010 | T-001,T-002,T-006 | version/revoke/restriction-confirmation tests |
| AC-011 | T-006 | semantics/fontScale 2.0 manual + UI test |
| AC-012–014 | T-001,T-003,T-004 | v1/v2 Sheets round-trip, durable outbox/category-disable/legacy-worker tests |
| AC-015–018 | T-002,T-004,T-007 | READY-only missing-original/archive/hash, retry/idempotency, remote-clear confirmation/isolation tests/manual SAF |
| AC-019–022 | T-001,T-003,T-006 | legacy body card/same route/no-observation/no-device tests |
| AC-023–027 | T-001,T-003,T-004 | legacy settings/job, AQ insert/preserve, v1/v2/tombstone/conflict/retry tests |
| AC-028–032 | T-005,T-006 | Health+InBody consent/strict failure/debug-redaction/public-HTTP/loopback-warning tests |
| AC-033–034 | T-004 | settings category/inBody-only-category tests |
| AC-035–037 | T-003,T-004,T-008 | import projection/no-derived-sheet/legacy-state regression tests |

## Commands and results

| Command | Result |
|---|---|
| Plan-only change: no Gradle command | not run; AGENTS.md prohibits full Gradle gates for documentation-only edits |
| `./gradlew :app:testDebugUnitTest --tests '*Migration9To10Test' --tests '*Migration1To10Test' --tests '*HealthSyncDaoTest'` | passed three times (final rerun: BUILD SUCCESSFUL in 9s; 38 actionable tasks, 11 executed, 27 up-to-date) |
| `./gradlew :app:testDebugUnitTest --tests '*Migration9To10Test' --tests '*Migration1To10Test' --tests '*HealthSyncDaoTest' --tests '*HealthRepositoryTest' --tests '*HealthTrendCalculatorTest' --tests '*MeasurementRepositoryTest' --tests '*BodyMeasurementRowParserTest' --tests '*SheetsRepositoryTest' --tests '*WorkoutImportRepositoryTest' --tests '*UploadMeasurementWorkerTest' --tests '*MeasurementUploadSchedulerTest' --tests '*MeasurementEditorViewModelTest' --tests '*MeasurementsViewModelTest'` | passed (T-002/T-003): BUILD SUCCESSFUL in 5s; 37 actionable tasks, 3 executed, 34 up-to-date |
| `./gradlew :app:testDebugUnitTest --tests '*SheetsRepositoryTest' --tests '*ConfigurationImportRepositoryTest' --tests '*ConfigurationSheetsRepositoryTest' --tests '*WorkoutImportRepositoryTest' --tests '*SettingsViewModelTest' --tests '*UploadMeasurementWorkerTest' --tests '*MeasurementUploadSchedulerTest' --tests '*InBodyReportAiReaderTest' --tests '*HealthAiEndpointPolicyTest' --tests '*WeeklyScheduleBackupRulesTest'` | passed (partial T-004/T-005): BUILD SUCCESSFUL in 9s; 37 actionable tasks, 4 executed, 33 up-to-date |
| `./gradlew :app:testDebugUnitTest --tests '*HealthReportAiReaderTest' --tests '*HealthAiEndpointPolicyTest' --tests '*HealthRepositoryTest' --tests '*HealthSyncDaoTest' --tests '*SheetsRepositoryTest' --tests '*UploadMeasurementWorkerTest' --tests '*InBodyReportAiReaderTest' --tests '*WeeklyScheduleBackupRulesTest'` | passed (T-004/T-005 incremental): BUILD SUCCESSFUL in 4s; 37 actionable tasks, 3 executed, 34 up-to-date |
| `./gradlew :app:testDebugUnitTest --tests '*HealthSheetsRepositoryTest' --tests '*HealthSyncWorkerTest' --tests '*HealthDocumentRepositoryTest' --tests '*HealthReportAiReaderTest' --tests '*HealthAiEndpointPolicyTest'` | passed (T-004/T-005 completion): BUILD SUCCESSFUL in 4s; 37 actionable tasks, 3 executed, 34 up-to-date |
| `./gradlew :app:testDebugUnitTest --tests '*AnalysisRenderTest' --tests '*AnalysisViewModelTest'` | passed (partial T-006): BUILD SUCCESSFUL in 13s; 37 actionable tasks, 11 executed, 26 up-to-date; render artifacts were not opened |
| `./gradlew :app:testDebugUnitTest --tests '*AnalysisRenderTest' --tests '*AnalysisViewModelTest'` | passed (T-006 editor/navigation incremental): BUILD SUCCESSFUL in 13s; 37 actionable tasks, 13 executed, 24 up-to-date; render artifacts were not opened |
| `./gradlew :app:testDebugUnitTest --tests '*AnalysisRenderTest'` | passed in earlier targeted T-006 runs; artifacts were not opened. Re-run only in W5 after UI-contract changes. |
| `./gradlew :app:testDebugUnitTest --tests '*HealthEditorViewModelTest' --tests '*HealthReportDetailViewModelTest' --tests '*AnalysisViewModelTest' --tests '*AnalysisRenderTest'` | passed (T-006 final): BUILD SUCCESSFUL in 8s; 37 actionable tasks, 3 executed, 34 up-to-date; render artifacts were not opened |
| `./gradlew :app:testDebugUnitTest --tests '*HealthArchiveExporterTest' --tests '*HealthArchiveViewModelTest' --tests '*HealthReportDetailViewModelTest'` | passed (T-007): targeted exporter/ViewModel/detail archive/delete contract; BUILD SUCCESSFUL in 3–4s; 37 actionable tasks, 3 executed, 34 up-to-date |
| `./gradlew :app:testDebugUnitTest` | passed for the frozen checkpoint: 957 tests, BUILD SUCCESSFUL in 16s |
| `./gradlew :app:assembleDebug` | passed for the frozen checkpoint: BUILD SUCCESSFUL in 7s |
| `./gradlew :app:assembleRelease` | blocked after code/resource compilation by missing local release-signing credentials; no code failure detected; repeat in W6 with configured keystore |
| `./gradlew :app:testDebugUnitTest --tests '*UploadMeasurementWorkerVersionTest'` | passed (frozen checkpoint): real Room DAO regression proves delayed exact v1 transient failure leaves current v2 projection/status unchanged; BUILD SUCCESSFUL in 5s |

## Frozen working checkpoint — 2026-09-04

Implemented code and tests are checkpoint evidence, not final AC sign-off. The full unit suite and
debug assembly pass. Release assembly reaches signing validation and requires the owner's local
keystore credentials; it remains a W6 gate. A version bump belongs only to resumed and completed
feature acceptance, not this checkpoint.

## Remaining work W1–W6

| Wave | Dependency-accurate remaining work |
|---|---|
| W1 | Final unreleased Room v10 report contract: PRELIMINARY/FINAL/CORRECTED/REVOKED; separate `collectedAt`/`reportedAt`/conditions/`originalExpected`; raw operator fidelity; explicit `canonicalKey` acceptance; entity/snapshot/migration/schema/payload update. |
| W2 | Editor/private docs: report-save idempotency; exact AI-config consent; restriction fields, atomic proposals/history/lift; robust health-document lifecycle/integrity; Clear Data wipes noBackup stores; bounded image decode. |
| W3 | Health Sheets protocol: final headers with managed prefix/user columns; absent-sheet handling; strict aggregate parser; duplicate/orphan/divergent conflict handling; preserve local `originalText`; cancellation; post-outer-commit cleanup. |
| W4 | History/conflicts/archive/navigation: builder route; all reports/restrictions including revoked/lifted reachable; AC-007 visible incompatibility reason; full field diff; retryable original deletion; freeze archive selection before SAF/recreation fail-closed. |
| W5 | Unified settings races: one-time legacy-init marker; fresh-ID disabled; serialize target/category changes and import-first rollback; cancel/pause workers around remote clear; cancellation propagation. |
| W6 | Integration closeout: AC-035/036 primary import→Analysis/no-derived-Sheets tests; docs/tracker reconcile; compare target and make one version bump only when the feature resumes/completes; final gates/manual checks. |

## Deviations

- v9→10 has no SQLite SHA-256 primitive. Its deterministic `v1|field=quote(value)` measurement
  payload and `$syncId:1` idempotency key are persisted with nullable `payloadHash`, explicitly
  marking the migrated v1 row unhashed rather than inventing a digest. Every newly written
  snapshot uses SHA-256.
- The conservative PDF limits (10 pages/20 MiB source/20 MiB request) are a documented
  implementation assumption awaiting code evidence, not a product deviation.

## Findings

- Current checkpoint targets unreleased Room v10 and retains the handwritten 9→10 migration plus
  full 1→10 regression coverage; W1 owns final schema/contract reconciliation.
- Current checkpoint uses versioned `Measurements` through managed A:AY while retaining legacy
  reads and jobs; W1/W3 own the remaining compatibility and protocol acceptance gaps.

## Residual risks

- External endpoint retention and publication/legal policy remain outside app enforcement.
- PDF provider behavior and SAF cancellation require manual device validation; originals cannot be
  recovered from Sheets by design.
- A lost request can result in a duplicate append attempt; idempotency and exact outbox ACK are
  mandatory, while PENDING local originals are deliberately excluded from archive.
- T-006 semantic nodes use the established selectable chips and 48dp action modifiers, but a
  dedicated fontScale=2.0/height assertion remains a manual accessibility follow-up; the
  targeted render gate completed without inspecting image artifacts.

## Gate P

Passed: all ACs map to tasks and checks; one writer has non-overlapping ownership; contracts and
waves precede dependent work; conditional gates and final sequential gates are explicit.
