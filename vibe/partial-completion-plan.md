# Partial completion — approved local work only

## Goal, scope and assumptions

Finish the already approved, partially implemented local roadmap slices without adding product scope or deploying anything. The source contracts remain the reviewed plans named below; their AC and task identifiers are immutable. This execution plan only orders their unfinished work and fixes ownership.

Included: CAL-01 local calendar; AI-01 Android exercise/InBody draft transport; calendar-AI's existing backend WIP plus its approved Android client; workout-notes; basic-profile; manual-health; coach-relations; and PLAN-01 only after the pending edited-preview decision. Excluded: CAL-02 Google sync, month UI, recommendations, nutrition, expanded metrics, health-source imports, and social backlog.

Assumptions frozen from the briefs: the app stays one `:app` module; Room is persisted-data SSOT; `BackendSync`/`PortableData` remain the sole sync boundary; every UI flow is immutable `StateFlow` plus one-shot events; work is main-safe and cancellation rethrows; no new dependency, logging, destructive migration, runtime permission, WorkManager, or foreground service is introduced unless its referenced contract already requires it. Existing feature contracts specify UI loading/empty/content/error/validation, TalkBack, 48dp targets, font scale 2.0, and compact/medium/expanded layouts.

## Scoped acceptance criteria

| ID | Delivered outcome | Governing contract |
|---|---|---|
| CAL-01.AC-001…007 | Room-owned offline plans/rules/exceptions, safe legacy migration, owner/capability-safe sync; no Google reconciliation. | [local-calendar-plan.md](local-calendar-plan.md) AC-001…007 |
| AI-01.AC-001…007 | Existing exercise and InBody actions use authenticated backend draft transport, validate editable drafts locally, and never require a client provider key. | [server-ai-plan.md](server-ai-plan.md) AC-001…007 |
| CAL-AI.B-AC-001…008 | Existing server calendar-AI capture/draft WIP is hardened against its frozen vectors, then the approved Android draft client is delivered through the current calendar flow. | backend `vibe/calendar-ai-plan*.md`, frozen fixture `42714e…b18e` |
| CAL-AI.AC-001…010 | Android calendar-AI draft request, preview, apply and recovery follow the accepted backend contract in the existing calendar surface. | Android `calendar-ai-brief.md` |
| NOTES.AC-001…009 | Workout/set notes and owner-scoped exercise hints persist, sync compatibly, and render in active/history/detail flows. | [workout-notes-plan.md](workout-notes-plan.md) AC-001…009 |
| PROFILE.AC-001…010 | Optional singleton profile, owner-safe portable sync, and the already approved 72-hour AI profile gate. | [basic-profile-plan.md](basic-profile-plan.md) AC-001…010 |
| HEALTH.AC-001…012 | Manual health ledger and consented sync/UI; measurements/InBody remain their existing separate source. | [manual-health-plan.md](manual-health-plan.md) AC-001…012 |
| RELATIONS.AC-001…008 | Android coach-relation consent and projection match the accepted backend relation contract, separately from proposal approval. | [coach-relations-brief.md](coach-relations-brief.md) AC-001…008 |
| PLAN-01.AC-001…009 | Proposal client uses authoritative server result then `BackendSync`; no locally synthesized routine/calendar/outbox. | [training-proposals-plan.md](training-proposals-plan.md) AC-001…009 |

## Current → target flow and frozen execution contracts

```text
Compose event → ViewModel event → repository/use case → Room transaction (SSOT)
  → Room Flow/stateIn(WhileSubscribed) → immutable UI state
  → BackendSync/PortableData owner mutex + durable outbox → accepted server capability/result
```

CAL-01 currently occupies the shared Room, legacy migration, `PortableData`, `BackendSync`, DI and calendar navigation paths. It finishes before every later Android data slice. Its migration must preserve sources and quarantine legacy Google recovery; CAL-02 is excluded.

AI-01 replaces only the existing Android direct-provider exercise/InBody transport with the accepted backend draft endpoint. Its minimal shared health-AI disclosure repository owns an owner-scoped durable exact consent operation/receipt, explicit grant/revoke, current notice receipt, capability and `X-Health-AI-Disclosure-Revision` header; no InBody image is encoded/uploaded before an enabled current receipt and late owner/revoke responses cannot apply. Health reuses this one repository/journal. AI-01 otherwise preserves local strict response validation and explicit user save; it does not add recommendation behavior. Calendar-AI is an already-started backend implementation: finish all V-001…011 test barriers, deterministic vectors, locking/replay/cancellation/privacy checks and independent review before an Android route is begun. It uses the existing calendar UI, with no new month presentation.

Notes, profile, health, relations, and proposals retain their own frozen payloads, consent records and capability filters. Health never copies `BodyMeasurementEntity`/InBody history. Proposal approval is always server-allocated then applied by a full authoritative `BackendSync`; proposal records are not `PortableData` before approval. Hilt bindings follow the established application/repository scopes; ViewModels own `viewModelScope`, repositories own injected IO/compute work and cancellation. Navigation routes retain IDs/versions in `SavedStateHandle`; durable drafts/journals live in Room.

**PLAN-01 decision (2026-09-10):** the user explicitly approved recipient-edited preview acceptance after server validation. The prepared backend patch is applied and passes independent review and full check/bootJar (169 tests). Immutable author versions and exact accepted request bytes remain preserved. Android implementation proceeds after its notes/profile prerequisites.

## Tasks, ownership and waves

One Android integration writer owns every application change after the current CAL-01 writer. This deliberate serial ownership covers `GymDatabase`, schemas/migrations, `PortableData`, `BackendSync`, Hilt modules, navigation, and version catalog/build file; no Android Gradle command runs while that writer edits. Backend writers work only in their backend checkout and never edit this repository. `CAL-01 writer` remains the exclusive owner of the current dirty calendar files until handoff.

| Task | Owner / exact files | Depends | Action | Automated verification | Observable done condition | AC |
|---|---|---|---|---|---|---|
| T-001 | CAL-01 writer: current files listed by `local-calendar-plan` T-002/T-004/T-005, including `data/calendar/*`, `data/db/{GymDatabase,dao/CalendarPlanDao}.kt`, calendar entities, `PortableData.kt`, `BackendSync.kt`, calendar UI/tests, `app/schemas/.../19.json` | current WIP | Close the explicit T-004 matrix: canonical remote conflict; tombstone/reference/date/DST bounds; Room17 account-link known/unknown coexistence; fail-closed recovery, interactive claim, sync and active-workout races. Correct migrated link owner and bridge-zone evidence without inferring owners or device zone. | inherited focused Calendar/PortableData/Room tests; `:app:compileDebugKotlin` | T-002/T-004/T-005 are done with no lost source, foreign replay, or unresolved Gate I case. | CAL-01.AC-001…007 |
| T-002 | Root integration owner: `vibe/local-calendar-plan-track.md` only after T-001; no production edit | T-001 | Run CAL-01 independent tester/reviewer, bounded fix by the CAL writer, record findings/version 38 only once, then final project gates. | `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug` | CAL-01 T-006…008 PASS; versionCode 38/versionName 1.3.30 is its sole increment. | CAL-01.AC-001…007 |
| T-003 | Backend calendar-AI writer: existing backend WIP only—migration `013-calendar-ai-attempts.sql`, `CalendarAiService.kt`, `CalendarAiAttemptRepository.kt`, `AiActionService.kt`, `AiController.kt`, schema/fixture/tests/tracker | CAL-01 contract and accepted AI backend base | Complete V-001…011 test vectors: source/capability barriers, replay/conflict/concurrency/crash recovery, cancellation commit, ticket/relation/catalogue/head/session/delete locks, validator/bounds/accounting/privacy and deterministic fixture vectors. | backend focused vectors/integration tests, then `./gradlew check` and `./gradlew bootJar` | Existing endpoint is accepted by independent T-005/T-006; no Android client has started against incomplete semantics. | CAL-AI.B-AC-001…008 |
| T-004 | Android integration writer, sole early disclosure Room owner: AI-01 files in `server-ai-plan` T-004/T-005 plus `HealthAiConsent{State,Outbox}Entity`, DAO, one N→N+1 migration/schema, `HealthAiDisclosureRepository`, minimal setting accessor, measurement UI/tests | T-002; accepted AI-01 and health backend baselines | Replace direct key/provider calls with authenticated draft transport, readiness/revision/cancellation guards and local editable-draft validation; remove client-key UX without altering explicit save behavior. Create the shared owner-scoped exact disclosure receipt/outbox aggregate: explicit grant/revoke, current notice receipt, capability plus exact revision header; no image encode/upload before enabled current receipt; guest never auto-consents; recheck owner/revoke before applying late response. | inherited focused AI/exercise/InBody/disclosure DAO/migration/claim/privacy tests; `:app:compileDebugKotlin`; final unsigned `:app:assembleRelease` attempt | AI-01 tracker records all AC evidence and exactly one next version increment; usable InBody has a grant path before full Health UI. | AI-01.AC-001…007 |
| T-005 | Android integration writer: approved calendar-AI client files frozen after T-003 (new repository/API/models, calendar ViewModel/screen/navigation, DI, focused tests) | T-003,T-004,T-002,T-006,T-007,T-011 | Implement only the approved backend draft request/preview/apply/retry flow in the existing calendar surface. Room remains SSOT; request body/journal and route state follow the backend fixture; no new calendar view or Google action. | focused CalendarAi repository/VM/Compose tests; `:app:compileDebugKotlin` | Calendar-AI Android AC evidence is present, with one feature version increment. | CAL-AI.AC-001…010 |
| T-006 | Android integration writer: exact files in `workout-notes-plan` T-004/T-005—entities/DAOs/migration/schema, `PortableData.kt`, `SyncSchema.kt`, `BackendSync.kt`, active/history/exercise UI, DI/tests, `app/build.gradle.kts` | T-002,T-004 | Implement the frozen notes/hints migration, owner/capability behavior, prune semantics and accessible adaptive editors. | inherited targeted migration/DAO/sync/active/history/exercise tests; compile; final unsigned `:app:assembleRelease` attempt | NOTES T-004/T-005 done, reviewer clear, one version increment. | NOTES.AC-001…009 |
| T-007 | Android integration writer: exact files in `basic-profile-plan` T-003…T-005—profile entities/DAO/migration/schema, profile repository/sync/DI, settings/profile UI and focused tests | T-006,T-004 | Implement the frozen nullable singleton and capability behavior, then the approved durable 72-hour profile AI prompt protocol. | inherited `*Profile*`, migration, BackendSync, settings/Compose tests; compile | PROFILE T-003…005 done, one version increment. | PROFILE.AC-001…010 |
| T-008 | Android integration writer: exact files in `manual-health-plan` T-003…T-007—health entities/DAO/migration/schema, health repository/sync/DI, Analysis/navigation UI and tests | T-007,T-006,T-004; accepted backend health baseline | Implement only the reviewed health ledger/consent projection; reuse AI-01-owned `HealthAiConsentStateEntity`/`HealthAiConsentOutboxEntity`, DAO and disclosure repository without a second migration/store, and rerun its relevant InBody privacy tests; preserve Measurements/InBody transport and owner/capability partitions. | inherited targeted health migration/repository/sync/VM/Compose and relevant AI privacy tests; compile | HEALTH Android tasks pass review and one version increment, with no second disclosure store. | HEALTH.AC-001…012 |
| T-009P | Contract owner: create `vibe/coach-relations-plan.md`, `vibe/coach-relations-plan-track.md` and any referenced byte-level relation fixture from the accepted brief | T-002,T-007; accepted backend relation baseline | Gate P the Android relation API/projection, consent/revoke/owner-switch contracts, exact Room/migration/sync ownership, navigation/restoration and test matrix. Preserve the eight approved brief ACs; do not expand product behavior. | fixture/vector validation where present; Gate P AC/task/test/ownership self-check | Android implementation has an accepted, exact contract and non-overlapping file ownership. | RELATIONS.AC-001…008 |
| T-009 | Android integration writer: exact Android files frozen by T-009P—relation API/repository/projection/DI, relation settings/inbox/navigation UI and focused tests | T-009P | Add relation consent/status UX and owner-scoped projection. It must not imply proposal approval or trainer health access. | focused relation repository/VM/Compose tests; compile | RELATIONS client contract and accessibility checks pass; one version increment. | RELATIONS.AC-001…008 |
| T-010 | Root contract/decision owner; PLAN-01 contract/tracker only when permitted | explicit user decision pending | Record the selected semantics: server stored canonical draft only, or approved locally edited preview equality behavior. Do not infer it. | fixture/vector checks from training-proposals T-001 | Blocking decision is concrete and byte-level contract is accepted. | PLAN-01.AC-001…009 |
| T-011 | Android integration writer: exact Android files in `training-proposals-plan` T-003…T-005—proposal Room journal/projection/migration/schema, API/repository/`BackendSync`/DI, proposal UI/navigation/tests | T-010,T-002,T-007; accepted backend PLAN-01 | Implement approved proposal client and relation authority integration. Persist canonical bytes before HTTP, recover response loss through server status/full `BackendSync`, and never manually insert calendar plans or dirty outbox copies. | inherited proposal migration/repository/BackendSync/VM/Compose tests; compile | PLAN-01 tracker T-003…005 passes with one version increment. | PLAN-01.AC-001…009 |
| T-012 | Root integration owner: only the applicable existing `*-plan-track.md` files | stable feature diffs | For each completed feature, run its independent review/test, one consolidated fix pass, then final Android gates sequentially. Follow AGENTS branch-isolation rules if scope requires it; do not commit, push or deploy without authorization. | per feature `:app:testDebugUnitTest`, then `:app:assembleDebug`; AI-01 and notes additionally retain their inherited unsigned release attempts | Every inherited tracker has AC→task→test evidence, no P0/P1, commands/results, deviations and residual risks. | all included ACs |

Execution waves: **1** T-001→T-002; **2** backend-only T-003 may run after CAL contract is stable; **3** T-004→T-006→T-007→T-008, with T-009P→T-009 at a separate serial point; **4** T-010→T-011 only after user decision; **5** T-005 after T-011, notes and profile context; **6** T-012 at each stable feature boundary. Backend T-003 may overlap Android work only because its files do not overlap. No other parallel implementation is allowed.

## File ownership

| Boundary | Sole owner | Rule |
|---|---|---|
| Current CAL-01 Room/calendar/sync WIP | CAL-01 writer | Handoff only after T-002; preserve all current dirty work. |
| All later `:app` production, tests, schemas and feature trackers | Android integration writer, sequential | One feature/migration/version at a time; owns shared Room, PortableData, BackendSync, DI, navigation and build choke points. |
| Calendar-AI backend WIP | backend calendar-AI writer | Backend checkout only; fixture and acceptance are handed off before T-005. |
| Final evidence and PLAN-01 decision | root integration/contract owner | Records outcomes; does not create product semantics. |

## Quality gates, risks and rollback

Always apply the architecture/UDF/dispatcher and traceability gates. Conditional gates: Room migration/schema incremental + full-path tests for CAL/notes/profile/health/proposals; capability/owner/outbox races for every synced slice; cancellation/recreation tests for AI/calendar-AI/proposals; Compose semantics/adaptive/font-scale tests for each UI slice. AI-01 and notes retain their explicit unsigned release attempts even without a newly introduced release-sensitive surface. No permission, WorkManager/service, chart-render, dependency, or screenshot gate is relevant unless a writer changes that bounded surface. Final project gates are the ordered unit suite and debug assembly, once per stable completed feature; documentation-only tracker updates run neither.

Risks: CAL legacy sources must remain preserved/quarantined; serial migrations must derive actual next `N`; calendar-AI cannot expose data beyond bounded approved capture; health must not duplicate measurements; version increments can collide if target changes, so compare immediately before each bump; no current authorization exists for commits/push/deploy. Rollback is forward-only: retain Room rows, migrations, durable drafts/journals, ACK baselines and server results; never use destructive fallback or replay legacy Google work.

## Gate P self-check

Every namespaced AC maps to a task and command above; T-001/T-002 preserve the already frozen CAL contract; shared Android ownership is serial; backend overlap has disjoint files; required Room/sync/UI conditional gates are listed and irrelevant gates excluded. The sole blocker is PLAN-01's explicit edited-preview decision. Recommended first implementation task: **T-001, finish CAL-01's explicit remaining matrix in the existing writer's current WIP.**

## Continuation ownership amendment — 2026-09-10

The user explicitly requests independent parallel agents in this handoff. This supersedes
only the former serial-all-Android allocation above; feature scope, accepted contracts and
feature dependencies remain unchanged. `shared_owner` is the sole writer of Room entities,
DAOs, migrations, exported schemas, BackendApi/BackendSync/SyncReady/PortableData/SyncSchema,
shared DI/navigation and app version integration. At most one additional implementation
writer owns a disjoint feature repository/UI/test boundary using agreed shared interfaces.
Root coordinates independent review, the only Gradle process, acceptance and tracker updates.
All existing WIP stays in the current feature checkout. A Gradle gate starts only after both
writers stop edits; no commit/push/merge/deploy is authorized.

The AI01 repair pair owns respectively shared transport/readiness regressions and
AI/disclosure/editor regressions. Notes starts after AI01 acceptance: the shared writer adds
its actual next Room migration and capability-aware sync, while the slice writer owns
active/history/exercise editors and tests. Shared repository interfaces use existing
`workoutId: String`, persistent `setId: Long`, and stable exercise syncId resolved inside the
hint repository; standard catalog mutation guards never disable personal hint actions.
