# Feature Brief — stage 17 / #19 manual health foundation

**Status:** pass. Product direction is sufficient; implementation prerequisites are the accepted typed backend capability/privacy contract, integrated [guest-sync](guest-sync-plan.md) and AI-01 Android contracts, and the actual Room version after profile/notes/CAL/guest-sync land. This checkout is Room **v16**; those predecessor records are not present here.

## Scope and non-goals

Scope: manual health reports, typed observations, confirmed restrictions, immutable correction history, tombstones, local-first storage, backend sync projection, and a fourth `HealthAnalysis` section. Existing `body_measurements` remains the sole source for measurements/InBody.

Non-goals: PDF/photo files, new OCR/AI features or direct provider calls, clinical thresholds or medical advice, Health Connect, a device catalogue, automatic restriction activation, trainer access, and a new dependency or Gradle module. The accepted AI-01 InBody action changes only to enforce the disclosure gate.

## Candidate acceptance criteria

- **AC-001.** A manual report, observation, and restriction work offline and survive process death before sync.
- **AC-002.** A report groups observations; each observation preserves original numeric/operator/range/category/text representation, unit, method, specimen/source, observed time, and entered time.
- **AC-003.** A correction creates a new version linked to the same logical record; the previous version remains auditable and does not become a separate observation.
- **AC-004.** A tombstone removes the current projection without recreating the record after sync; history remains auditable.
- **AC-005.** Trends use only compatible observation series. Unit, method, specimen, or metric incompatibility prevents merging and explains why.
- **AC-006.** `HealthAnalysis` is the fourth internal Analysis section; it reads each existing `BodyMeasurementEntity.id` once by reference. Measurements never enter the health ledger/outbox or become copied/heuristically deduplicated `HealthObservation` records, and their existing `measurement:<id>` transport remains unchanged.
- **AC-007.** Versioned local acknowledgement, backend sync consent, and AI health disclosure consent are distinct durable product states. Current acknowledgement gates only new local confirmation and never deletes old data. The existing AI-01 InBody action is denied both locally and by backend authorization without current disclosure consent, and no health request/result/error is debug-persisted. Sync does not imply AI disclosure; trainer access is never implicit.
- **AC-008.** Guest-created records remain local and offline and join the accepted `GUEST → CLAIMED(B, mergeId) → OWNED(B after all ACK)` Room transition before token installation. B-only recovery is durable; A-owned data is never reclassified as guest.
- **AC-009.** Backend rejects cross-owner records, malformed typed payloads, unsupported kinds/capabilities, and invalid tombstones before ledger/cursor advancement.
- **AC-010.** Health has an isolated baseline/cursor/exact outbox. Consent/capability off preserves it byte-for-byte while non-health sync proceeds; an old pre-partition mixed operation must settle unchanged before health is enabled, then a capable full refresh precedes send.
- **AC-011.** Sync retry, cancellation, process death, owner A→B→A, and concurrent device edits produce no duplicate logical records or silent version loss. Client UUID versions are immutable, server ingestion assigns ordering once, and stale head CAS retains all versions while the server head remains current.
- **AC-012.** TalkBack names section state/content, controls meet 48dp targets, and the fourth section remains reachable at font scale 2.0/adaptive widths.

## Old Sheets AC → backend AC mapping

| Old health-data AC | Current backend equivalent |
|---|---|
| AC-001, AC-004–011, AC-017 | AC-001–005, AC-008, AC-011: manual local records, representation, corrections, compatible trends, offline/idempotency |
| AC-012–014, AC-017–018 | AC-007–011: owner-bound backend records, durable explicit sync consent, capability filtering, revision/conflict/tombstone behavior |
| AC-015–016 | Deferred to stage 18: no attachment transport or archive claim in stage 17 |
| AC-019–022 | AC-006: retain `BodyMeasurementEntity`/`MeasurementsViewModel` SSOT and expose it by reference |
| AC-023–027, AC-033–037 | Replace Sheets categories/rows/version imports with backend kinds, capability/privacy filtering, `CloudRecord` revisions, and local computed projections only |
| AC-028–032 | Deferred to stage 18: no document upload, OCR, new AI endpoint, or provider logging path in this stage; only AI-01 InBody authorization is tightened |

## Current execution and data flow

- `GymNavGraph` routes `analysis` to `AnalysisScreen`, which currently has Overview/Load/Progress only; it also routes the existing pushed `measurements` screen. Evidence: `ui/navigation/GymNavGraph.kt:53`, `:227-239`.
- `AnalysisViewModel` derives UI state from completed-workout flows and `AnalyticsEngine`, with heavy work on `@ComputeDispatcher`; a health aggregate must join its own Room flows without moving calculation to Main. Evidence: `ui/analysis/AnalysisViewModel.kt:116-160`.
- `MeasurementsViewModel` observes `BodyMeasurementDao.observeAll()`, filters/computes off Main, and owns existing measurement history. Evidence: `ui/measurements/MeasurementsViewModel.kt:94-125`.
- `BodyMeasurementEntity` is one wide nullable record with `id`, `measuredAt`, InBody and circumference fields; it has no health-report relation or version/tombstone. Evidence: `data/db/entity/BodyMeasurementEntity.kt:17-66`.
- `PortableData.snapshot()` emits current measurements as `measurement:<id>`; `apply()` overwrites by ID. This is existing body-measurement transport only and cannot represent health history/tombstones yet. Evidence: `data/backend/PortableData.kt:161-163`, `:337-347`.
- `BackendSync` serializes sync under one mutex and currently has one mixed exact outbox/baseline. Manual health must add a health-only partition; an already durable mixed operation is settled byte-identically before the capability can be enabled. Evidence: `data/backend/BackendSync.kt:24`, `:64-112`, `:155-277`.
- Generic `CloudRecord` has kind/id/revision/deleted/payload but no health capability or privacy field. Evidence: `data/backend/BackendModels.kt:16-45`.
- Backend owner state is local `backend_state`; `claim()` is the owner-switch boundary. Evidence: `data/backend/SyncSchema.kt:10-58`, `data/backend/BackendSync.kt:64-112`.
- The current app has no health repository, report/observation/restriction entities, typed backend health contract, health consent store, or server capability response.

## Affected files and layers

- Room: `GymDatabase.kt`, handwritten `MIGRATION_N_N+1`, schema `app/schemas/.../N+1.json`; new health entity/DAO files under `data/db/entity` and `data/db/dao`.
- Data/domain: a single health repository plus typed value/comparability/version logic; `flowOn(@ComputeDispatcher)` for analysis transformation.
- Sync: `PortableData.kt`, `BackendSync.kt`, `BackendModels.kt`, `BackendApi.kt`, `SyncSchema.kt`, worker behavior, and DI bindings.
- UI: `AnalysisScreen.kt`, `AnalysisViewModel.kt`, `GymNavGraph.kt`, health ViewModel/screen/editor files, plus the existing InBody action in `MeasurementEditorViewModel`; `MeasurementsScreen`/`MeasurementsViewModel` remain measurement SSOT.
- Tests: Room migration/DAO, repository version/tombstone/claim tests, `BackendSyncTest`, ViewModel tests using live state collection, and targeted Compose accessibility/render coverage.

## Project invariants

- Room is local SSOT; no destructive migration.
- `body_measurements` is never copied into, matched with, or captured by the health ledger/baseline/cursor/outbox; UI references are deduplicated only by the existing measurement ID.
- Derived charts/summaries are projections, never sync records.
- Current `PortableData` local-ID versus UUID mapping and `BackendSync.mutex` owner/active-workout guards remain intact.
- No `Log.*`; health data, raw documents, model bodies, and backend error bodies must not be exposed.
- UI follows `docs/design-system.md`: `GymMotion`, `GymHaptics`, Material colors, and accessible scrollable section controls.
- Existing generic sync must retain unsupported records rather than drop/rebuild them.

## Assumptions and resolved product direction

The September 3 Sheets plan made medical categories opt-in and excluded them from sync by default. The later global directive supersedes the transport decision: confirmed structured health records participate in full backend sync. Preserve the user’s explicit health-sync consent as product data; no agent confirmation is needed.

Keep three boundaries separate: local recording consent, backend sync consent, and external AI disclosure consent. “Sync all” does not grant AI access or human-trainer access. A trainer receives no health data unless a later explicit, scoped sharing capability is designed.

## Android-specific risks and recommended verification

- **Schema race:** actual N may exceed 16 after profile/notes/CAL. Freeze N and add exactly one handwritten migration only after predecessor integration.
- **History corruption:** current measurement updates overwrite by ID; health versions need immutable version rows/current projection and tombstone tests.
- **Capability downgrade:** generic `CloudRecord` presently cannot filter health safely inside a mixed exact request. Freeze dedicated health baseline/cursor/outbox, settle an old mixed operation unchanged before enablement, and verify accepted → missing capability → accepted full-refresh recovery while non-health advances.
- **Owner leakage:** extend [guest-sync](guest-sync-plan.md), rather than the old destructive claim: `GUEST → CLAIMED(B, mergeId)` commits in Room before tokens; B-only recovery and all-ACK precede `OWNED`; A→B purges A health state atomically.
- **Concurrency:** exercise two client UUID corrections from one head, serverSequence idempotence, stale head CAS/server-wins current, cancellation, response loss, exact worker retry, and process death.
- **AI privacy:** AI-01 removes BYOK/direct provider/logging first; this stage gates its existing InBody frontend before image encoding and requires owner-scoped backend disclosure authorization, including revoke/late-response tests.
- **UI:** verify fourth section at font scale 2.0, keyboard/TalkBack navigation, empty/error/loading states, and comparability explanation without color-only meaning.
- No manifest, permission, background service, dependency, or release-only change is required in this manual-record stage.

## Official sources used

none. FHIR is only a semantic reference from the older plan; this brief does not introduce FHIR interchange.

## Files changed

- `vibe/manual-health-brief.md`
