# Partial completion tracker

| Task | Status | Owner | AC / verification | Done condition |
|---|---|---|---|---|
| T-001 | done | CAL-01 writer | CAL-01.AC-001…007; focused Calendar/Room/PortableData tests + compile PASS | Explicit T-004 matrix closed; two independent P1 findings fixed and rechecked. |
| T-002 | done_local | Root + CAL-01 writer | CAL-01.AC-001…007; independent T/V PASS; full1127/0fail/1skip, debug PASS | CAL tracker T-008 accepted; version38/1.3.30 once. |
| T-003 | done_local | backend calendar-AI writer | CAL-AI.B-AC-001…008; independent T/V PASS; full166tests/0fail/0skip/check/bootJar PASS | Backend and Android client accepted locally. |
| T-004 | done_local | Android integration writer, sole early disclosure Room owner | AI-01.AC-001…007; draft/disclosure DAO+migration/claim/privacy tests, compile, unsigned release attempt | Authenticated editable drafts and shared disclosure grant path replace direct provider path. |
| T-005 | done_local | root + UI writer | CAL-AI.AC-001…010; focused tests + compile | Existing calendar draft client accepted after PLAN-01 plus notes/profile context. |
| T-006 | done_local | shared_owner + ai_fix | NOTES.AC-001…009; T/V PASS, full1124/0fail/1skip + debug/R8 PASS; release signing unavailable | Notes/hints feature accepted at Room22/version40. |
| T-007 | done_local | shared_owner + ai_fix + root | PROFILE.AC-001…010; independentT/V PASS, full1160/0fail/1skip + debug PASS | Profile/gate accepted at Room23/version41. |
| T-008 | done_local | root + shared_owner | Health data/migrations targeted PASS; 9 sync + 6 Analysis Compose PASS; debug PASS | Implemented at Room24/version42. Final integrated T-012 PASS. |
| T-009P | done | Contract owner | RELATIONS.AC-001…008; independent strict Gate P recheck PASS | Exact Android relation plan/contract and implementation accepted. |
| T-009 | done_local | root | RELATIONS.AC-001…008; focused relation tests + compile | Relation client accepted. |
| T-010 | done | User + root contract owner | Explicit yes; backend full169/0fail/0skip/check/bootJar PASS | Recipient-edited draft accepted after validation; author snapshot and exact approval bytes retained. |
| T-011 | done_local | root + UI writer | PLAN-01.AC-001…009; 94 targeted tests accepted after 17-test recheck; debug PASS | Edited approval/recovery/owner/Room25 and Compose accepted. |
| T-012 | done_local | Root integration owner | all; review + `:app:testDebugUnitTest`, `:app:assembleDebug` on final stable integration | Trackers contain final evidence/no P0/P1. |

## AC-to-task-to-test traceability

| Contract AC | Tasks | Required evidence |
|---|---|---|
| CAL-01.AC-001…007 | T-001,T-002 | Existing CAL focused migration, legacy, capability, owner-race, ViewModel/semantics tests; full Android gates. |
| AI-01.AC-001…007 | T-004,T-012 | Existing AI draft transport, cancellation, exercise/InBody validation and UI tests; compile, unsigned release attempt and feature final gates. |
| CAL-AI.B-AC-001…008 | T-003,T-012 | Backend V-001…011 deterministic vector/integration/review gates. |
| CAL-AI.AC-001…010 | T-005,T-012 | Android CalendarAi repository/VM/Compose tests and final gates. |
| NOTES.AC-001…009 | T-006,T-012 | Referenced notes migration/DAO/sync/prune/UI tests, unsigned release attempt and final gates. |
| PROFILE.AC-001…010 | T-007,T-012 | Referenced profile migration/sync/owner/prompt race/UI tests and final gates. |
| HEALTH.AC-001…012 | T-008,T-012 | Referenced health migration/version/consent/sync/UI plus reused disclosure privacy tests and final gates. |
| RELATIONS.AC-001…008 | T-009P,T-009,T-012 | Relation Gate P contract, owner/consent/repository/VM/Compose tests and final gates. |
| PLAN-01.AC-001…009 | T-010,T-011,T-012 | Accepted edited-preview fixture; proposal journal/recovery/BackendSync/Compose tests and final gates. |

## Deviations

None accepted. This plan deliberately does not restart frozen feature plans, introduce new acceptance criteria, or authorize Git/external publication.

## Findings and command results

- CAL-01 current branch `feat/local-calendar` has production WIP and schema v19; its tracker records focused tests and compile PASS, but explicitly leaves the T-004 matrix open.
- Root verified byte-identical fixture parity and JSON parsing: CAL-01 `d841e…9911`, AI `f76033…599b`, notes `5a98…18b`, profile `1bec…ed6`, health `33d74…f5c0` (Android vibe copy versus backend test resource: PASS).
- Calendar-AI backend has real local WIP at backend `91905b4`; its 013 migration/capture endpoint and eight focused capture tests exist, while V-001…011 completion/review/final gates remain open.
- AI backend, notes backend, profile backend, health backend, coach-relations backend and safe PLAN-01 backend subsets are locally accepted according to their referenced trackers; their Android slices remain pending.
- No command ran for this plan. Per AGENTS.md, plans/trackers alone do not run Gradle.

### CAL-01 independent acceptance checkpoint

Independent T/V found two P1 defects: generated cloud graph ordering and recurring commands
using display date instead of original instance date. Both were fixed and independently
rechecked PASS. Narrow XML reports: BackendSyncTest 44 and CalendarViewModelTest 22,
all passing without skips; compile and spotless PASS. Full suite started with the existing
Mac isolation init script, log `/private/tmp/yarumo-partial-cal01-full.log`.
This is application validation, not a documentation-only Gradle run. Final acceptance awaits
full-suite completion and debug assembly.

First full gate FAILED: 1127 tests, 3 failures, 1 skipped. Migration9To12Test,
Migration10To12Test and Migration11To12Test encounter duplicate `capabilityOwner` at
18→19. Original writer diagnoses historical fixture versus migration correctness before
another full run. AI-01 app implementation has not started.

## Residual risks

- PLAN-01's edited-preview decision is resolved by explicit user approval; backend gates pass, Android prerequisites remain pending.
- CAL-01's legacy owner and timezone migration defects and final application gates are accepted locally.
- Every later Android feature needs its own single version increment; CAL-01's existing 38/1.3.30 increment must not be repeated.

CAL01 final acceptance:1127tests/0fail/0errors/1skip and assembleDebugPASS. IndependentT/V PASS;
version38/1.3.30 Room19. ControlAPK/private/tmp/yarumo-cal01-v38-debug.apk. Local branch now
feat/partial-completion with all known changes retained; AI01T004 starts next.

AI01 T004/T005 in progress: Room20 disclosure state/outbox/migration, exact owner-scoped
consent repository, capability union, final-ACK readiness adapter and backend draft binding
are being integrated. Direct provider files are removed; obsolete settings references and
focused tests remain. Gate I/T/V and final app gates are not yet accepted.

Backend calendar-AI targeted70tests PASS before narrow recheck. Remaining second-pass findings:
avoid hydrating overridden standard payloads, preserve immutable attempt replay after revision
changes, separate unauthorized from stale-context errors, and align EXPLAIN with both time bounds.
Writer repairs this packet; final backend check/bootJar is not yet run or accepted.

Superseding final backend result: all second-pass findings independently rechecked PASS.
Root `check bootJar` PASS58s,166tests/0fail/0errors/0skip; log
`/private/tmp/yarumo-partial-calendar-ai-final.log`. Backend calendar-AI is done_local,
no deploy/commit. Its Android client still waits for notes/profile/proposals.

## Handoff continuation — 2026-09-10

The existing `feat/partial-completion` WIP is retained in place; backend WIP on
`feat/calendar-ai` is read-only. No publication or Git commit is authorized.
The erroneous positional `ExerciseEntity` constructor in BackendAiRepositoryTest is
fixed with named arguments. The handoff targeted AI command (seven test selectors,
compileDebugKotlin and spotlessCheck, JDK21) passes: 66 tests, zero failures/errors/skips,
41 tasks. Log: `/private/tmp/yarumo-partial-ai-handoff-targeted.log`.
This supersedes only the initial compile blocker, not final AI acceptance.
Independent AI review and test coverage audit are running.

Current ownership supersedes the former all-Android serial writer allocation in the
execution plan in accordance with the user's explicit parallel-agent request:
`shared_owner` alone owns Room/entities/DAOs/migrations/schema, shared sync/PortableData,
DI/navigation/build integration. Independent slice writers receive disjoint files and
frozen interfaces before edits; only root coordinates Gradle, with no concurrent writer
or second Gradle process. Notes and relation preparation are currently read-only.
PLAN-01 edited-preview change remains unapplied pending the explicit user answer.

AI01 independent recheck found remaining issues, so T-004 is not accepted:
- Recheck consent/owner after readiness and photo encoding, before upload.
- Serialize consent draining and atomically preserve a newer revoke intent during ACK.
- Guard late results using monotonic session/cache identity, including editor mapping.
- Use literal raw request bytes for consent replay.
- Enforce strict request/response DTO keys, primitive types and bounds.
- Keep revoke reachable during an in-flight grant and test font-scale-2 semantics.
Two disjoint writers are fixing shared transport/sync and AI/disclosure/UI respectively.
The real transport 401 regression is also required; the initial fake-flag assertion alone
is insufficient evidence. No additional version bump is due for these AI01 fixes.

PLAN-01 decision received explicitly: user answered `yes` to permitting a recipient-edited
preview after server validation, retaining immutable author version and exact approval bytes.
The proposed patch `/private/tmp/yarumo-proposal-edited-approval-proposed.patch` passed
`git apply --check` against existing backend WIP and was applied without touching unrelated
changes. Two backend regressions cover edited materialization/replay and invalid-edit atomicity.
T-010's decision is resolved; backend verification is in progress before T-011 may start.
No publication is authorized. Targeted log: `/private/tmp/yarumo-proposal-edited-targeted.log`.

Superseding PLAN-01 backend final result: `check bootJar` PASS53s,169tests/0fail/0errors/0skip. Independent narrow review PASS; COACH edited-approval authority/audit regression added. Log `/private/tmp/yarumo-proposal-edited-final.log`. No remaining product-decision blocker.

AI01 repair verification checkpoint: all 113 targeted cases now pass across the combined
run (112 pass) and the isolated corrected HealthAiDisclosureRepositoryTest rerun (PASS).
The sole intervening failure was a virtual-time test timeout around real Room IO; the
regression now awaits actual durable false intent before releasing the stalled grant.
The direct suspended-editor lookup and ABA cache tests are present; independent narrow
Gate T and P1 review PASS. Full JDK21 unit gate is running with the existing isolation init
script, no excluded tests: `/private/tmp/yarumo-partial-ai-full.log`. Debug/release follow.

AI01 final acceptance: full1091/0fail/0errors/1skip + assembleDebug PASS; minifyReleaseWithR8 PASS. assembleRelease attempted, blocked solely by missing signing configuration. Room21/version39/1.3.31 retained. Control APK `/private/tmp/yarumo-ai01-v39-debug.apk`. T-006 Notes begins with Room21 predecessor.

Notes Gate I first attempt generated Room22 successfully and found only an unsupported
seven-flow typed combine in ActiveWorkoutViewModel; writer split it into typed <=5-flow
stages. Independent Notes T/V requested a focused fix/test bundle before acceptance:
active hint edit/unpin actions, unified save/unpin ordering and stale target checks,
exercise_hint capability/owner/exact-outbox coverage, annotated pending/tombstone coverage,
and explicit note reorder/duplicate/cascade evidence. The two original owners are applying
that bundle; Notes is not accepted yet. Log `/private/tmp/yarumo-partial-notes-targeted.log`.

Notes independent narrow T/V now PASS, including literal first-dispatch and retry byte
comparison plus durable noncanonical JSON. Production/Hilt/test compilation passes after
updating the eight existing WorkoutDao fakes. The first executable targeted run exposes
two JUnit cleanup signatures and five new BackendSync test failures; the shared owner is
diagnosing those against the frozen capability/outbox contract. Full Notes acceptance
is still pending; no new feature code starts during the gate.

Notes capability recheck: targeted65 PASS, then final BackendSync-only PASS after guarding
fresh capture whenever a durable outbox remains. Independent narrow review PASS. The
regression retains literal mixed hint/annotation bytes across lost ACK, capability loss
and a later compatible measurement edit, then replays the original before the later edit.
Root full unit gate is running: `/private/tmp/yarumo-partial-notes-full.log`.

Remaining Android dependency order is Profile → Health → PLAN-01 → Coach relations →
Calendar-AI. PLAN-01 can now precede relations because its backend authorization contract
is accepted; completing its recipient client first satisfies relation T-004 coach authoring
without accepting an incomplete relation feature. This only reorders the same authorized
tasks; it adds no scope or publication.

Notes final acceptance: full1124/0fail/0errors/1skip (6m40s), debug and R8 PASS.
Release attempt fails solely for unconfigured signing. Room22/version40/1.3.32;
control APK `/private/tmp/yarumo-notes-v40-debug.apk`. Profile starts next with frozen
parallel domain/UI interfaces and one shared DB/sync owner. No code gate remains running.

Read-only Calendar-AI preflight confirms backend fixture v2 `42714ea…1b18e` and additive
`POST /v1/ai/calendar-drafts`. Android will use its own typed draft action with the existing
SyncReady/raw owner+epoch/no-401-retry boundary and the shared Profile prompt gate. The
response carries a PLAN-01 proposal; it must open that recipient preview/apply flow and
must not invoke manual CalendarPlanRepository.createPlan or fabricate local aggregates.
Exact request fields remain those in the accepted fixture. Implementation is still pending.

Profile final acceptance: independent T/V PASS, full1160/0fail/0errors/1skip (6m53s),
assembleDebug PASS12s. Room23/version41/1.3.33; control APK
`/private/tmp/yarumo-profile-v41-debug.apk`. Health contract copies all match
`33d74a…2305f5c0`; shared domain/UI boundary is in `manual-health-android-api.md`.
The same two disjoint owners start Health after all Profile gates finish. No publication.

## Current continuation checkpoint — supersedes old workflow waves

User-selected workflow is read from `/private/tmp/valerochka-codex-workflow/AGENTS.md` and its
Android implementation skill. Targeted checks between internal slices; one final full unit/debug
gate for stable integration. Root owns integration and all Gradle; shared_owner owns current
Health sync corrections only. No commit/publication or live AI calls.

- Existing branch `feat/partial-completion` and all WIP retained. AI/Notes/Profile acceptance above
  remains valid; edited-proposal backend permission is already granted.
- Health initial compilation restored: extra closing brace and nullable JSON parent fixed.
  `compileDebugKotlin compileDebugUnitTestKotlin` PASS (`/private/tmp/yarumo-health-resume-compile.log`).
- First `assembleDebug` PASS; APK `app/build/outputs/apk/debug/app-debug.apk`.
- Health + incremental/full Room24 migration targeted run: 50 tests, 1 failure, 0 errors/skips.
  Sole failure: section selector text placement at fontScale2. Root replaces crowded segmented
  row with existing adaptive chips at 48dp; recheck pending.
- Independent narrow sync review identified four real remaining defects: Room rollback on late
  validation failure, parent-first offline batches, active-workout gate, full refresh before
  possibly-dispatched retry. shared_owner fixes these with direct regressions.
- Early compile of this packet found one suspend method-reference error at HealthLedgerSync:489;
  original owner fixes it before next run. No repeated full/release gate.
- Root prepared pure PLAN01 models/canonical serializer and fixture-vector tests; runtime
  repository/Room/UI integration still pending after Health targeted acceptance.
- No emulator launch is claimed. Existing Health Compose tests exercise real production content.

Health targeted corrections are verified: DAO transaction context removes deadlock; nine sync
regressions pass, six Analysis Compose tests pass, initial remaining Health/migrations passed.
Root now owns all Room/shared integration. PLAN01 implements Room25 (three owner-scoped
journals), strict API/byte serializer (six tests pass), and receive-only normal BackendSync
projection. One PLAN01 version increment is 43/1.3.35. Early compile and repository/migration
tests are next; UI writer owns only stateless proposal screens/Compose tests.

## Completion status — current

**All originally authorized started features are implemented and locally accepted.**
Current branch `feat/partial-completion`; Room26; app version45 / 1.3.37.
All existing WIP remains uncommitted. No commit/push/merge/deploy, live AI or publication.

Final stable-tree validation:
- Full `:app:testDebugUnitTest` + `:app:assembleDebug`: **BUILD SUCCESSFUL** (8m45s),
  **1262 tests, 0 failures, 0 errors, 1 conditional skip**.
- Skip: `EmulatorV11CopyMigrationTest` requires an external `VALEROCHKA_GYM_DB_COPY`,
  not provided for this run. Normal incremental/full historical migrations passed.
- `AnalysisRenderTest`: 9/9 PASS; generated images were not opened or analyzed.
- Log: `/private/tmp/yarumo-partial-final-full.log`.
- APK: `/private/tmp/yarumo-partial-v45-debug.apk`; manifest verified45/1.3.37.
  SHA256 `5b237c50a3c2be2c0a07daa55481d110d3f5aff5e9550ebe53b82f427818b1d8`.
- `:app:minifyReleaseWithR8`: **BUILD SUCCESSFUL** (1m41s), log
  `/private/tmp/yarumo-partial-final-r8.log`. No signing/package-release task ran.

Accepted feature integration:
- PLAN01: author snapshot separate from editable draft; immutable approval bytes;
  server validation and authoritative full sync commit one linked projection. Exact retry,
  active-workout/manual-conflict and account-round-trip regression tests pass.
- Relations: transient invitation secret, independent grants, directory and allowlisted
  calendar/completed projections, bilateral revoke, coach proposal create/revise/revoke.
  Owner-bound durable mutation journal retains exact bytes, pending duplicate protection,
  and sanitized invitation metadata. No private projection cache in Room/PortableData.
- Calendar AI: typed intent only after SyncReady and the shared72h profile gate; future
  slot/exclusions/notes opt-out; verified proposal opens PLAN01. No direct local plan creation.
- UI checked through Compose semantics, including fontScale2 and independently labelled
  consent controls. No device/emulator interaction or screenshot inspection claimed.

Targeted acceptance preceding final integration:
- PLAN01 94 tests covered; two fixture failures fixed and affected17/17 rechecked.
  `/private/tmp/yarumo-proposal-targeted.log`, `/private/tmp/yarumo-proposal-recheck.log`.
- Relations/Calendar AI/profile:34/34 tests PASS;
  `/private/tmp/yarumo-relations-calendar-targeted.log`.
- One narrow independent read-only owner/token/retry/access review: PASS, no P1.
- Backend previously accepted full169 tests/check/bootJar; no backend change in this finish.

Contract fixture copies match backend byte-for-byte:
Relations `f5960d8a8fd269518aa6347b18607a778e4eff7f64aa5ecd9a2dc529aa501460`;
Calendar AI `42714ea6086c8d7349543cfdb3d11cfac86d04fe67b15ec743ff388f4e31b18e`.
