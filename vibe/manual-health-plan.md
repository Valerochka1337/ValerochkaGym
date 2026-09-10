# Manual health foundation — stage 17 / #19

Slug: manual-health. Status: executable after backend #19/#50 publishes its typed capability/fixture contract, the accepted [guest-sync contract](guest-sync-plan.md) and AI-01 Android path are integrated, and the integrated Room predecessor is known. This replaces only old Sheets transport decisions; mapped accepted behavior remains.

## Goal, scope, non-goals, assumptions

Deliver offline manual health reports, typed observations, confirmed restrictions, auditable corrections/deletions, typed backend projection, and the fourth Analysis section. body_measurements remains sole SSOT for measurements/InBody: Health references it by ID and never writes a copied observation.

In scope: Room ledger/current projections; compatible numeric trends; separate local, backend-sync and AI-disclosure choices; guest claim/owner isolation; UI states/navigation/restoration/accessibility. Backend validation and strict fixtures land first.

Out: Sheets; PDF/photo/attachments; new OCR/AI features or direct provider calls; clinical thresholds/advice/generated references; Health Connect; trainer sharing; permissions; new dependency/module; new worker/service; device catalogue. The already accepted AI-01 InBody action is changed only to enforce the disclosure gate. Stage 18 owns documents/archives.

Assumptions: checkout v16 is stale relative to profile/notes/CAL/guest-sync; sole Room writer freezes predecessor N before edits and adds only N to N+1. Backend #19/#50 overrides Sheets. AI-01 has already replaced Android BYOK/direct-provider transport with the authorized backend InBody action and removed the debug AI logger before this stage. Signed finite values permit at most 18 integral and 12 fractional digits, including -2.5; original text is retained. No positive-only or clinical assumption.

## Acceptance criteria

| ID | Criterion |
|---|---|
| AC-001 | Confirmed manual reports, observations and restrictions save offline and survive recreation; guest save works. |
| AC-002 | Observation preserves report link, original value/operator/range/category/text, unit, method, specimen/source, observed-time precision and entered time. |
| AC-003 | Correction appends a linked immutable version; history shows it once under its logical record. |
| AC-004 | Tombstone hides current projection after round-trip without destroying history or resurrection. |
| AC-005 | Only compatible numeric observations trend; incompatibility is explained and units never silently convert. |
| AC-006 | HealthAnalysis is the fourth Analysis section and reads each BodyMeasurementEntity ID once by reference; measurements never enter health storage/sync or become copied/heuristically matched observations, and existing measurement transport remains unchanged. |
| AC-007 | Versioned local acknowledgement, backend-sync consent and AI-disclosure consent are durable/distinct. Current acknowledgement is required for a new local confirmation but never deletes or hides earlier data; InBody AI is blocked locally and by backend authorization without current explicit disclosure consent; no health request/result/error is debug-persisted; trainer access is never implicit. |
| AC-008 | Health participates in the accepted `GUEST → CLAIMED(B, mergeId) → OWNED(B after all ACK)` transition: the Room claim precedes token installation, B-only recovery survives recreation, A/B cannot cross, and only separately confirmed full erase may physically delete owner data. |
| AC-009 | Backend rejects cross-owner, malformed, unsupported and invalid-tombstone payloads before ledger/cursor change. |
| AC-010 | Missing capability/consent preserves the isolated health ACK baseline/cursor and byte-exact dirty health outbox while non-health sync proceeds; enabling waits for any pre-partition mixed operation to settle unchanged, then performs a capable health full refresh before sending. |
| AC-011 | Retry, cancellation, recreation, A→B→A and concurrent corrections create no duplicate logical record or silent version loss. `versionId` remains immutable and idempotent, server ingestion assigns ordering once, and head CAS conflict keeps every version while the server head wins only the current pointer. |
| AC-012 | TalkBack, 48dp controls, fontScale 2.0 and compact/medium/expanded layouts keep Health reachable. |

Old health-data AC-001/004–011/017 map to AC-001–005/008/011; AC-012–014/017–018 to AC-007–011; AC-019–022 to AC-006; AC-023–027/033–037 to AC-007–011. Old AC-015–016 and AC-028–032 defer to stage 18. Existing Measurements backend transport/consent is unchanged.

## Current and target flow

Current: Analysis has Overview/Load/Progress; AnalysisViewModel derives workout analytics. MeasurementsViewModel owns BodyMeasurementDao.observeAll(). PortableData overwrites measurements; singleton BackendSync owns mutex/owner/outbox/baseline. No health repository, entities, consent or capability exists.

    editor event → HealthViewModel → HealthRepository Room transaction
      → immutable version + logical head/current projection → Room Flow
      → fourth Analysis section (measurement IDs only)
      → isolated health outbox when owner + health-sync consent + healthLedgerV1
      → typed backend append versions + head CAS → union versions + server-wins head

Room is SSOT for ledger, heads, guest-sync owner phase, isolated health baseline/cursor/outbox and durable backend AI-consent receipt state. DataStore is SSOT for the local privacy choices; token store remains credential SSOT. BackendSync remains singleton serialization/cancellation owner; repository owns health append/projection transactions, while the accepted guest-sync claim transaction remains the only owner transition. UI never reaches DAO/backend. Heavy series grouping uses flowOn ComputeDispatcher; immutable UI state uses stateIn WhileSubscribed(5000).

## Frozen contracts

### Ledger and typed payload

HealthLogicalRecord has logicalId UUID, ownerId nullable, kind REPORT|OBSERVATION|RESTRICTION, createdAtEpochMs, currentVersionId nullable, deleted Boolean.

HealthRecordVersion has immutable client-generated versionId UUID, logicalId, parentVersionId nullable, serverSequence nullable, state CONFIRMED|TOMBSTONE, enteredAtEpochMs, payloadJson. Ownership is stored once on HealthLogicalRecord/the accepted guest-sync aggregate, not duplicated into immutable version rows; claim changes the aggregate binding without rewriting audit versions. Only versionId is the cross-device identity. `serverSequence` is absent before upload, transitions once from null to the value assigned by server ingestion, and is idempotently returned for the same equal versionId; clients never allocate it and no `(logicalId, client sequence)` uniqueness exists. Identity, parent, state, timestamps and payload never change, and a non-null serverSequence is never replaced. A logical head selects one known version; projections derive from it, so corrections do not double-count. A report tombstone hides child projections but retains audit rows.

The strict, versioned payload union is:

- report: title, sourceText, observedAt, observedPrecision DATE|DATETIME;
- observation: reportLogicalId, metricIdentityId, metricNameOriginal, valueKind NUMBER|COMPARATOR|RANGE|CATEGORY|TEXT, valueOriginal, optional parsed signed decimal(s), operator LT|LE|GT|GE|EQ, unitOriginal, methodOriginal, specimenOriginal, sourceOriginal, referenceOriginal, observedAt, precision, enteredAt;
- restriction: textOriginal, confirmedAt. Draft restriction text is SavedState only and never ledger/outbox.

All originals, operator and date precision survive exactly. User-provided references display as text, never advice. Metric identity is explicitly selected/created; names never auto-merge. A numeric trend needs equal metric identity plus byte-equal unit, method and specimen and two live numbers. Any mismatch renders a reason/table, never a line.

`body_measurements` never enters the health ledger, health baseline/cursor/outbox or metric-name matching. HealthAnalysis reads `BodyMeasurementEntity` rows by their existing ID and renders each ID once even when several cards/series reference it; its existing `measurement:<id>` backend transport and consent remain unchanged. A health observation with similar name/date/value is a separate manual record and is never heuristically deduplicated against or converted from a body measurement.

Backend healthLedgerV1 accepts health_report, health_observation and health_restriction as immutable versions plus a separate head intent `{logicalId,currentVersionId,baseHeadRevision}`. It checks owner, UUID linkage, enum/value shape, parent/report target, same-versionId byte equality and tombstone target before ledger/cursor mutation. First ingestion assigns a monotonic serverSequence; exact retry of the same equal versionId returns that assignment, while unequal reuse rejects before mutation. It unions all distinct versions. A matching head CAS advances `currentVersionId` and `headRevision`; a stale CAS retains the submitted version but returns the server head unchanged. Android applies that server head and keeps the losing version in audit history; a later user correction is a new UUID, never a rewrite or automatic clone/rebase. Accepted/rejected/concurrent-head/lost-ACK fixtures freeze these results before Android work.

### Consent, sync, owner and execution

DataStore has `localHealthStorageAcknowledgedVersion=0`, `healthBackendSyncEnabled=false`, and `healthAiDisclosureEnabled=false`. The app has a frozen current local-storage notice version. Reading existing local health never requires a new acknowledgement; confirming a new report/observation/restriction requires the current version and can then work offline/guest. Raising the notice version prompts only at the next confirmation: it never deletes, hides, tombstones or uploads existing data and does not change either transfer consent. There is no revoke operation for an historical acknowledgement.

AI-01's authorized backend InBody action is the only health AI path. `healthAiDisclosureEnabled` gates the frontend before image encoding/upload and is also materialized as an owner-scoped backend authorization version/receipt; the action endpoint rechecks it before accepting bytes and after provider completion. Local revocation blocks immediately and queues/retries only the consent revocation, never an image; a stale/absent backend authorization fails closed. AI consent grants neither general backend health sync nor trainer access. No health image, prompt, response, provider/error body or parsed result enters debug logs or durable diagnostic storage; AI-01's logger-removal invariant remains enforced.

Health sync uses dedicated Room partitions: `health_sync_baseline`, `health_sync_state(owner, cursor, needsFullRefresh)` and a health-only exact-request outbox. A health operation never shares request JSON or operationId with the existing non-health outbox. Before `healthLedgerV1` can be enabled on an upgraded install, any already durable pre-partition mixed operation must reach its accepted ACK or definite-rejection resolution with identical bytes; it is never decoded, filtered or rewritten to extract health. Only after that row is settled can separate health capture begin.

When consent is off or capability absent, health is hidden: no health request, ACK, baseline mutation, cursor advance, outbox cleanup or partial health snapshot apply. Already captured health request bytes and operationId remain exact; non-health sync proceeds through its own request. Re-enable first performs a health-scoped capable full refresh from a frozen endpoint, unions versions, applies server heads, preserves dirty request bytes, and then retries the pending health operation idempotently. An ambiguous dispatched operation retries exact bytes; a definite rejection retains evidence and creates a new operation only after the full refresh and recomputation. Backend fixtures cover off/capability-loss during captured, dispatched and ACK windows.

Existing backend mutex, unique work/backoff and active-workout/owner guards remain. Cancellation rethrows. Responses/apply/ACK recheck token plus the Room owner phase transactionally. Health extends the already accepted [guest-sync state machine](guest-sync-plan.md): the single Android owner changes `GUEST` to `CLAIMED(B, mergeId)` in Room before B tokens are installed, atomically binding the full health ledger, heads and health sync metadata to that claim without deletion. Crash/token loss remains `CLAIMED(B)` and only B can resume; the state reaches `OWNED(B)` only after all guest merge batches, including health when consent/capability permit it, are ACKed under the guest-sync final-equality rule. `OWNED(A)→B` purges A health ledger/baseline/cursor/outbox in the same established account-switch transaction and never treats A as guest. Local storage acknowledgement and DataStore consents are not owner proof and do not weaken the Room transition. Only separately confirmed account full erase may physically delete health rows.

### UI, DI, navigation and restoration

AnalysisSection.Health follows Progress in the existing Analysis route; no bottom-nav change. Detail/editor are pushed routes in GymNavGraph. Health has loading, empty with manual add, content, validation and retryable error. HealthViewModel exposes immutable state/events; SavedStateHandle stores only draft ID, selected record/series and route args; confirmed content reloads Room after death.

Use GymScreenScaffold/GlowBackground, AnalysisCard, GymCard, ScrollableChipRow, native TrendLineChart and ValueRow only for compatible series. Charts always have textual table/selected value. Use Kotlin strings, Material colors/shapes, GymMotion and gymHaptics only. Use compact 16dp; medium/expanded 24dp plus bounded/list-detail layout; semantics/status text and 48dp Canvas alternatives are required. Repository/database/DAOs are singleton; ViewModels use Hilt; compute dispatcher injects. One writer owns navigation/Hilt/version choke points.

## Tasks

| Task | Exact files / owner | Depends | Action | Automated verification | Done condition | AC |
|---|---|---|---|---|---|---|
| T-001 | Android writer — vibe/manual-health-plan.md and vibe/manual-health-plan-track.md | — | Freeze integrated N, profile/notes/CAL/guest-sync/AI-01 order; record accepted guest-sync state schema, AI-01 fixture/version and absence checks, health queue partition version and backend fixture hash. | checklist, no Gradle | Prerequisite versions/contracts/fixtures recorded; no Android source begins before accepted backend schema. | AC-001–012 |
| T-002 | Backend writer, external backend repo — healthLedgerV1 version/head schema, health cursor/full-refresh, capability response, owner-scoped AI disclosure authorization, validators and strict fixtures | T-001 | Implement backend first; freeze exact JSON, same-version collision, serverSequence assignment, head CAS/server-wins result, health-only operation idempotency, consent authorization, accepted/rejected/capability-loss/full-refresh fixtures Android uses verbatim. | backend validator/integration commands recorded in tracker | Reject-before-cursor proof; exact retry, union, concurrent head, consent revoke/race and full-refresh fixtures pass and are version/hash recorded. | AC-007,009–011 |
| T-003 | Same Android writer, sole Room owner — data/db/entity/HealthLogicalRecordEntity.kt, HealthRecordVersionEntity.kt, HealthMetricIdentityEntity.kt, HealthSyncBaselineEntity.kt, HealthSyncStateEntity.kt, HealthSyncOutboxEntity.kt; data/db/dao/HealthDao.kt, HealthSyncDao.kt; data/db/GymDatabase.kt, Migrations.kt; data/backend/SyncSchema.kt; app/schemas/.../N+1.json; tests HealthDaoTest.kt, HealthSyncDaoTest.kt, MigrationNToNPlus1Test.kt, Migration1ToNPlus1Test.kt | T-002 | Entities/indexes/FKs; client UUID versions with nullable serverSequence; atomic append/head/tombstone; isolated health baseline/cursor/exact outbox; extend accepted guest claim/purge transaction; handwritten migration. | app testDebugUnitTest targeted HealthDao/HealthSyncDao/migrations | Incremental/full paths preserve existing data and pre-partition outbox bytes; concurrent client versions coexist; history/head/guest phase/owner/schema pass. | AC-001–004,008,010,011 |
| T-004 | Same Android writer — data/health/HealthModels.kt, HealthRepository.kt, HealthConsentStore.kt, HealthComparability.kt; di/DataModule.kt, DomainModule.kt only if needed; HealthRepositoryTest.kt, HealthConsentStoreTest.kt, HealthComparabilityTest.kt | T-003 | Main-safe local flow, parser, explicit metric identity, versioned local acknowledgement, independent sync/AI consent and compatible projections. | app testDebugUnitTest targeted repository/consent/comparability | Raw forms/negative decimals survive; current acknowledgement gates only new confirmation; version increase preserves rows and other consents; no silent merge/reference advice. | AC-001–005,007 |
| T-005 | Same Android writer, sync/owner choke point — data/backend/BackendModels.kt, BackendApi.kt, PortableData.kt, BackendSync.kt, BackendSyncWorker.kt, SyncSchema.kt; data/ai/BackendAiRepository.kt only for disclosure authorization integration; BackendSyncTest.kt, BackendAiRepositoryTest.kt; BackendModule.kt only if needed | T-002–004, accepted guest-sync and AI-01 Android | Consume frozen fixtures; settle any pre-partition exact operation unchanged before enabling health; separate health/non-health capture; exclude body measurements from health capture while preserving existing measurement transport; freeze/refresh/union/head-CAS and definite/ambiguous retry; extend the accepted GUEST→CLAIMED→OWNED and A→B transactions; transfer/revoke owner-scoped AI authorization without health payload. | app testDebugUnitTest targeted BackendSync/BackendAiRepository | Mixed legacy request is never rewritten; measurements retain only `measurement:<id>` transport; off/capability-loss keeps exact health bytes while non-health advances; null→B/restart/B-only resume/all-ACK, A→B→A, retry/cancel, two concurrent versions/one server head and AI authorization races pass. | AC-006–011 |
| T-006 | Same Android writer, navigation/privacy choke point — ui/analysis/AnalysisViewModel.kt, AnalysisScreen.kt, HealthAnalysisScreen.kt, HealthViewModel.kt, HealthEditorScreen.kt; ui/measurements/MeasurementEditorViewModel.kt and its existing InBody consent UI; ui/navigation/GymNavGraph.kt; tests HealthViewModelTest.kt, HealthAnalysisComposeTest.kt, MeasurementEditorViewModelTest.kt and affected InBody Compose test | T-004–005 | Fourth section, push UI/editor, states/restoration, measurements by reference, accessible/adaptive trend table; require current AI disclosure consent before encoding/upload, fail closed on stale backend authorization, and retain manual InBody entry. Assert AI-01 logger/direct-provider absence; add no-health-debug-persistence check. | app testDebugUnitTest targeted HealthViewModel/HealthAnalysisCompose/MeasurementEditor | No copied measurement; disclosure-off/revoked sends zero image requests; no health debug persistence; state/semantics/font/adaptive/UI states pass. | AC-001–007,012 |
| T-007 | Same Android writer — app/build.gradle.kts; tracker | T-006 | Single versionCode/patch bump; evidence. | app compileDebugKotlin | Exactly one bump; no catalog/dependency change. | AC-001–012 |
| T-008 | Read-only tester + strict reviewer; tracker findings | stable T-007 | Audit AC/races/migration/worker/UI and contracts in parallel. | missing targeted command only; reviewer no Gradle | No P0/P1 or one consolidated packet. | AC-001–012 |
| T-009 | Android writer — only confirmed T-003–007 files; tracker | T-008 if needed | One fix pass, smallest retest, focused review. | affected command | P0/P1 resolved; P2 explicit. | affected |
| T-010 | Root session — tracker | T-008/T-009 | Final stable gates. | app testDebugUnitTest, then app assembleDebug | Results recorded against all ACs. | AC-001–012 |

## Ownership, waves, gates, risks

| Boundary | Owner | Rule |
|---|---|---|
| Backend ledger/capability/fixtures | backend writer | T-002 completes before Android production work. |
| Android production/tests/Room/schema/navigation/Hilt/version | one Android writer | T-003–007; no overlap. |
| Entity+DAO+migration+database+schema | same writer | T-003 atomic. |
| Verification | tester + strict reviewer | read-only after stable diff. |
| Final gates | root | T-010. |

Waves: T-001 → T-002 → serial T-003–007 → parallel T-008 → conditional T-009 → T-010. No Gradle while writer edits.

Relevant gates: handwritten incremental/full migration and exported schema; transaction/FK/index tests; client UUID/serverSequence/head-CAS races; isolated queue/cursor and pre-partition exact-operation settlement; off/capability-loss/lost-ACK retry; accepted guest-sync claim/recovery/all-ACK; versioned acknowledgement and frontend/backend AI-disclosure denial with no health debug persistence; compute-dispatcher/cancellation; unique-work/backoff/idempotency; Hilt scope; Compose restoration/loading/empty/error/content, semantics, 48dp, fontScale 2.0, compact/medium/expanded. Run AnalysisRenderTest only if chart rendering changes; never inspect its screenshots without user request. No permission/manifest/service/dependency/release/attachment/OCR/provider-transport gate applies.

Risks: T-002 is release-blocking; predecessor N may change; Room/DataStore/credential stores cannot transact together. The accepted guest-sync ordered Room-claim-before-token protocol contains that window. Health capability cannot be enabled until an old exact mixed operation has settled; it is never rewritten for migration. Forward rollback preserves version/tombstone rows and both sync partitions; acknowledgement version increase and normal consent disable never delete data. Account full erase is the sole physical-delete path. No destructive migration, immutable-version rewrite or client sequence uniqueness.

## Gate P self-check

PASS: AC-001–012 map to T-002–010 and tests; one Android writer owns all mutable files and extends the accepted guest-sync/AI-01 choke points; Room is unsplit; backend fixtures freeze version/head/cursor/capability/AI-authorization contracts before Android; health queue partition and pre-partition settlement preserve exact retries; only relevant gates are included.
