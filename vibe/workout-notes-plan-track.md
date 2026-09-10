# Stage 10 / #9 tracker

## Current integration preflight — 2026-09-10

CAL-01 is locally accepted at Room19/version38; AI-01 is accepted at Room21/version39.
Notes uses handwritten 21→22 migration, preserving calendar and consent data, and one
version increment to 40/1.3.32. Android implementation is undergoing final verification.

Read-only seam audit: `ActiveWorkoutRepositoryImpl.finish` prunes via `WorkoutSetEntity.isBlank`;
notes-only incomplete sets must survive while completion-only filters in
`SaveCompletedWorkoutAsRoutineUseCase` and `RoutineUpdateUseCase` remain unchanged. Narrow DAO
text writes use set ID plus expected workout; numeric/completion writes retain WorkoutSetMutator.
Hints use stable exercise syncId and independent storage/actions outside STANDARD catalog guards.
Current Calendar capability code must be generalized for `annotated-workout-writes` and
`exercise-hint`; preserve both current and acknowledged note-state when filtering legacy payloads.
No new worker/outbox is introduced. Targeted old-migration fixture cleanup must remove the new
notes tables/columns before replaying historical migrations, as in CAL/AI fixtures.

| Task | Status | Owner | Dependencies | AC | Automated check |
|---|---|---|---|---|---|
| T-001 | done | root contract owner | CAL-01 strategy | AC-006–AC-008 | all three copies SHA256 5a98f491bbb658c76e47933b49b50041fe1f1a16618b9cadfcf1befdbd22818b |
| T-002 | done_local | backend writer | T-001 | AC-006–AC-008 | backend integration/bootJar — not run |
| T-003 | pass | backend tester + Sol/high reviewer | T-002 | AC-006–AC-009 | backend audit/recheck — not run |
| T-003F | pass | backend writer / reviewer / root | T-003 | AC-006–AC-009 | conditional fixes, recheck, final backend suite |
| T-004 | done_local | shared_owner | T-001,T-003F,03/12/CAL | AC-001–AC-004, AC-006–AC-009 | targeted migration/DAO/sync pass; exact retained-outbox repair passes |
| T-005 | done_local | ai_fix | T-004 | AC-001–AC-005, AC-009 | targeted VM, active/detail Compose, routine exclusion tests pass |
| T-006 | done | independent tester + reviewer | T-005 | AC-001–AC-009 | coverage audit and final source recheck PASS; no P0/P1 |
| T-006F | done | original writer / reviewer | T-006 | AC-001–AC-009 | capability filtering and held-outbox fixes pass narrow tests/review |
| T-007 | done_local | root | T-006F | AC-001–AC-009 | full1124/0fail/0errors/1skip + debug/R8 PASS; release attempted, signing unavailable |

## AC traceability

| AC | Tasks | Evidence |
|---|---|---|
| AC-001 | T-004,T-005 | Unicode/trim/recreation/double-save tests |
| AC-002 | T-004 | persistent-set-ID/reorder/duplicate/cascade tests |
| AC-003 | T-004,T-005 | history and notes-only prune/program-copy tests |
| AC-004 | T-004,T-005 | hint/standard/unpin invariance tests |
| AC-005 | T-005 | active/detail semantics and adaptive tests |
| AC-006 | T-001–T-004 | GET sync/changes/list/single, POST atomic old-shape/rejection and capability-transition tests |
| AC-007 | T-001–T-004 | both owner capabilities, before-paging, mixed batch/dirty/exact-retry merge/owner tests |
| AC-008 | T-002–T-004 | server-wins/tombstone tests |
| AC-009 | T-003–T-005 | active block/post-finish/dirty-baseline tests |

## Findings and residual risks

- Legacy strategy is resource-specific annotated-workout rejection, never blanket v4.
- `STANDARD` guard protects catalog mutations only; hint operations mutate an independent personal row.
- Actual migration predecessor follows integrated CAL-01/12 work.
- Notes are private typed future-AI data, never instructions or permissions.
- No command ran: planning files only.

Root strict corrections: T-003F/T-006F explicitly own conditional writer fixes and narrow review. Incapable workout projection is sendable only if both current AND acknowledged-baseline set notes are empty; pending annotated outbox bytes remain immutable. Finish/program tests explicitly named in T-005.

Gate P strict Sol/high recheck PASS. All remaining P0/P1/P2 closed; exact baseline/current projection and fix-loop ownership frozen.

Root fixture/new clarification bounded Sol-high review PASS; STANDARD cross-kind UUID guard and canonical hint UUID validation assigned to backend writer. Backend branch feat/workout-notes-contract from a5b567a. Android fixture waits its own feature branch.

Backend f26de34 accepted: final full check/bootJar68tests0failures/errors/skips. Two stale migration-count expectations updated8→9; all focused P2 assertions added.

## Android final acceptance — 2026-09-10

Independent T/V PASS after the active hint actions, save/unpin race, paired capability
filtering and retained exact-outbox repairs. Full JDK21 unit suite passes: 1124 tests,
zero failures/errors, one existing skip; 6m40s, no exclusions. `assembleDebug` and
`minifyReleaseWithR8` pass. `assembleRelease` was attempted and fails only at
`validateSigningRelease` because release signing is not configured; no signing material
was requested or changed. Room22/schema export and version40/1.3.32 are verified.

Logs: `/private/tmp/yarumo-partial-notes-full.log`,
`/private/tmp/yarumo-partial-notes-build.log`, `/private/tmp/yarumo-partial-notes-release.log`.
Control debug APK: `/private/tmp/yarumo-notes-v40-debug.apk`. All WIP remains uncommitted;
no publication. Later Profile work uses this accepted Room22 predecessor.
