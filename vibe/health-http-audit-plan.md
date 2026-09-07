# Health report HTTP exception and Health audit — implementation plan

Status: approved Feature Brief / strict, temporary path. Slug: `health-http-audit`.

## Goal, scope, and acceptance criteria

Permit a user-confirmed, configured `http://` endpoint to receive a selected **health study/lab-report** document temporarily, while keeping every other health/AI recipient policy unchanged and identifying bounded Health-tab follow-up work.

In scope: report-only endpoint decision, existing consent disclosure, reader/editor tests, a focused Health-tab audit, an `ARCHITECTURE.md` exception note, and one version increment `25 / 1.3.17` → `26 / 1.3.18`. Out of scope: manifest, URL-setting, Room/schema/migration, network client, permissions, background work, navigation redesign, auto HTTPS downgrade, and changes to InBody or restriction interpretation.

Assumptions: `usesCleartextTraffic=true` and URL settings already accept HTTP/HTTPS; the approved recipient configuration is frozen at disclosure; the currently dirty report parsing/UI-polish edits belong to completed work and are preserved.

| AC | Acceptance criterion | Verification |
|---|---|---|
| AC-001 | A report upload accepts valid HTTP public-host and LAN endpoints temporarily; HTTPS remains allowed, malformed URLs remain blocked, and no automatic scheme change occurs. | T-001 focused policy/reader tests |
| AC-002 | The exception applies only to report upload. Shared policy keeps public HTTP rejected for InBody and restriction interpretation, and a clearly named rollback TODO restores the shared decision. | T-001 source review + policy tests |
| AC-003 | Before document render/API use, the editor uses the already-disclosed recipient/model; existing local HTTP consent remains, and public HTTP is warned as HTTP rather than called local. Cancel makes no render/API call. | T-002 ViewModel tests |
| AC-004 | Saving while a document is reading is impossible; a successful parse binds its original, while cancellation/failure retains the prior draft/original association. | T-002 ViewModel tests |
| AC-005 | Loading a correction preserves the target draft’s `originalExpected`; a text-only correction cannot silently clear a requested original. | T-002 ViewModel tests |
| AC-006 | Missing-original detail reflects `report.originalExpected`, not provenance; missing/denied renderer metadata becomes a safe reader failure. | T-003 renderer/detail tests |
| AC-007 | Health-tab audit records bounded, evidence-backed fixes for root triage; the period-filter discrepancy is recorded pending product clarification without broad redesign. | T-004 audit record |
| AC-008 | No persistence, navigation/state-restoration, Hilt scope, dispatcher/cancellation, worker, permission, or build-policy contract regresses; version and final project gates pass. | T-005 review + final gates |

## Current → target flow

`Settings recipient → HealthEditorViewModel endpoint decision → disclosure → frozen AiApiRequestConfiguration → HealthReportAiReader endpoint decision → renderer (compute/cancellation-aware) → AiApi → immutable draft + matching pending original → explicit save → HealthRepository/Room SSOT`.

Target changes only the two report checkpoints to call `healthReportAiEndpointDecision`. It delegates to `healthAiEndpointDecision`; when and only when the shared answer is `PublicHttpRejected`, it returns `Allowed` with `TODO(health-report-http)` to delete the wrapper and return the shared answer on rollback. HTTPS, loopback consent and invalid outcomes pass through unchanged. There is no new repository/domain, Hilt binding/scope, dispatcher, Room transaction/migration/schema, WorkManager, permission, or navigation/state-restoration behaviour.

## Frozen decisions and contracts

- `healthAiEndpointDecision` remains the SSOT for generic health AI policy and remains unchanged for InBody/restriction callers.
- `healthReportAiEndpointDecision(baseUrl, loopbackHttpConsent)` is private-to-report policy adaptation with the exact four-result sealed contract; public HTTP maps only there. The TODO names the temporary exception and rollback action.
- `AiDisclosure` continues to carry the selected URI, parsed host and frozen model/configuration. Add an explicit `httpWarning` derived from the actual scheme, separate from existing `loopbackWarning`; dialog language must say HTTP transport for public HTTP and “local HTTP” only for loopback.
- UI state remains immutable and event flow stays UI event → ViewModel → state/one-shot channel. Dialog state is screen state; confirmed report data remains Room-owned only after explicit save. Existing saved-state and route contracts remain untouched.
- The reader must make the decision before `renderer.render` and `api.createCompletion`; it preserves cancellation and injected compute dispatcher for parsing. No logging, new dependency, Hilt change, cleartext manifest change, or background operation.
- Save is unavailable during read. Success replaces draft and binds only that read URI as pending original; cancel/failure leave the prior editable draft and its original association intact. Loading a correction initializes `retainOriginal` from the target draft’s `originalExpected`. Renderer metadata/prepare failures return the existing safe reader failure, never escape before its guarded error mapping. Report detail’s original-copy status keys from `originalExpected` independently of a lab’s provenance string.

## Tasks and execution waves

| Task | Owner | Exact files | Depends on | Action | Automated verification | Done condition | AC |
|---|---|---|---|---|---|---|---|
| T-001 Report-only policy and reader | Implementer A | `app/src/main/java/com/valerochka1337/valerochkagym/data/ai/HealthAiEndpointPolicy.kt`; `app/src/main/java/com/valerochka1337/valerochkagym/data/ai/HealthReportAiReader.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/data/ai/HealthAiEndpointPolicyTest.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/data/ai/HealthReportAiReaderTest.kt` | frozen contracts | Add wrapper; replace only reader gate; add public hostname/LAN, HTTPS, invalid and loopback coverage plus real fake-API HTTP request. Keep shared callers unchanged. | `./gradlew :app:testDebugUnitTest --tests '*HealthAiEndpointPolicyTest' --tests '*HealthReportAiReaderTest'` | Public HTTP reaches fake API only through report reader; rejected/invalid paths do not render/send; shared policy still rejects public HTTP. | AC-001, AC-002, AC-008 |
| T-002 Editor disclosure, save/read/correction integrity and tests | Implementer A | `app/src/main/java/com/valerochka1337/valerochkagym/ui/health/HealthEditorViewModel.kt`; `app/src/main/java/com/valerochka1337/valerochkagym/ui/health/HealthEditorScreen.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/health/HealthEditorViewModelTest.kt` | T-001 | Use report wrapper before disclosure; expose actual-scheme warning; prevent save while reading and bind original only on successful parse; preserve prior draft/original on cancel/failure; initialize correction `retainOriginal` from target `originalExpected`. | `./gradlew :app:testDebugUnitTest --tests '*HealthEditorViewModelTest' --tests '*HealthReportAiReaderTest'` | Public HTTP wording is explicit, frozen config sends only after confirmation, no confirm invokes neither renderer nor API, a stale draft cannot save against a new URI, and text-only correction preserves expected original. | AC-001, AC-003, AC-004, AC-005, AC-008 |
| T-003 Renderer and report-detail resilience | Implementer B | `app/src/main/java/com/valerochka1337/valerochkagym/data/ai/HealthDocumentRenderer.kt`; `app/src/main/java/com/valerochka1337/valerochkagym/ui/health/HealthReportDetailViewModel.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/data/ai/HealthDocumentRendererTest.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/health/HealthReportDetailViewModelTest.kt` | frozen contracts | Move the renderer’s first two URI resolver/metadata accesses inside its guarded failure path; add missing/denied URI Robolectric tests. Replace detail’s provenance-based missing-original decision with `originalExpected`; cover LAB provenance plus successful/failed original storage status. | `./gradlew :app:testDebugUnitTest --tests '*HealthDocumentRendererTest' --tests '*HealthReportDetailViewModelTest'` | Missing/denied URI produces the renderer’s safe failure; report expected to retain an original reports missing-copy regardless of provenance, while a report that never requested it does not. | AC-006, AC-008 |
| T-004 Bounded Health-tab audit | Root triage owner | `vibe/health-http-audit-plan-track.md` | T-001–T-003 evidence | Record confirmed defects fixed by T-001–T-003 and the period-filter discrepancy pending behaviour clarification. Do not redesign the screen. | `git diff --check` | Tracker contains evidence/disposition; no speculative redesign is implemented. | AC-007 |
| T-005 Documentation, version, review and final gates | Root | `ARCHITECTURE.md`; `app/build.gradle.kts`; `vibe/health-http-audit-plan-track.md` | T-001–T-004 | Architecture/version are already root-owned at `26 / 1.3.18`; confirm temporary report-only exception and rollback TODO, review scope/ownership and run final gates sequentially. | `git diff --check`; `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug` | Architecture describes the exception accurately; version is exact; no P0/P1 review finding; both final commands pass. | AC-002, AC-007, AC-008 |

W1: T-001 then T-002 by Implementer A, with T-003 in parallel by Implementer B after contracts freeze; file ownership does not overlap. Root runs one combined targeted gate only after both writers finish. W2: independent tester/reviewer verification, then T-004/T-005 finalization by Root.

## Ownership

| Owner | Exclusive boundary |
|---|---|
| Implementer A | Report-only policy/reader and editor disclosure/save integrity with their named tests. No manifest, settings URL policy, InBody, restriction interpreter, Room, schema, DI, or build edit. |
| Implementer B | `HealthDocumentRenderer` guarded URI metadata failure and `HealthReportDetailViewModel` missing-original status, with their named tests only. |
| Root | `ARCHITECTURE.md`, sole `app/build.gradle.kts` version edit, audit triage, tracker completion, final review/gates. |

## Relevant quality gates

- Always: AC traceability, smallest reliable test layers, no logs/mocks/dependencies, final test then assemble sequence.
- Architecture/coroutines: retain injected compute dispatcher and cancellation; UI never bypasses report reader/repository boundaries.
- Compose: immutable state, lifecycle collection, semantics/live error, 48dp dialog actions, font-scale/adaptive review of the changed disclosure; no screenshot/render inspection.
- External API/security: validate URL and recipient before file preparation/request; preserve denied/cancelled path and no data in logs.
- Not applicable: Room/migration/schema, Hilt/module scope, WorkManager/service, permissions/manifest, dependency/R8/release build (none change).

## Risks, rollback, unresolved questions

- HTTP exposes medical document bytes to a network transport. Scope is intentionally limited to a report read after a visible recipient/model confirmation; no auto-downgrade and no persistence change mitigate accidental expansion.
- Rollback: remove `healthReportAiEndpointDecision`/its calls and tests, restore `healthAiEndpointDecision` at both report checkpoints, remove the architecture exception, retain existing loopback consent and shared policy. Stored reports/documents remain valid because no data format changes.
- The public-host vs private/LAN distinction is deliberately irrelevant during the temporary exception: every syntactically valid non-loopback HTTP report recipient is accepted after disclosure. The Health period-filter discrepancy is recorded for product clarification, not implemented here; security owner must choose rollback timing before release hardening.

## Gate P self-check

PASS: AC-001…008 each map to a task and executable or review verification; ownership is non-overlapping; report adapter, frozen recipient, consent wording, save/read/correction integrity, renderer failure mapping, detail status and rollback contract are frozen before implementation; only relevant quality gates are included.
