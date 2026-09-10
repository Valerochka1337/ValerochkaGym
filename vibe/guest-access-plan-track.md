# Guest local access — implementation tracker

Status: **Gate P strict PASS; implementation waits for accepted dependencies and T-001 source inventory.**

## Dependency inventory (T-001)

Fill from the actually integrated accepted implementations before source edits. Expected paths are hints from accepted plans, not completion evidence.

| Dependency | Actual symbol/file | Evidence | Status |
|---|---|---|---|
| guest-sync ownership row and DAO | TBD (expected under `data/backend/BackendModels.kt`, `GuestMergeDao.kt`) | accepted `vibe/guest-sync-plan.md`; inspect integrated source | BLOCKED |
| Room predecessor and exported schema | `N = TBD` in `data/db/GymDatabase.kt` | inspect immediately before T-002 | BLOCKED |
| owner-switch preservation preflight | TBD | accepted guest-sync amendment and tests | BLOCKED |
| claim/ACK/account state flow | TBD (`BackendSync`/`AccountViewModel` integration) | accepted guest-sync tests | BLOCKED |
| AI readiness and transport | TBD (expected `SyncReadyAdapter`, `BackendAiRepository`) | accepted `vibe/server-ai-plan.md` and backend auth evidence | BLOCKED |
| local calendar repository | TBD (expected `CalendarPlanRepository`) | accepted `vibe/local-calendar-plan.md` | BLOCKED |
| Google Calendar transport/worker | TBD (expected `CalendarGoogleSyncRepository`, `CalendarSyncWorker`) | accepted `vibe/google-calendar-sync-plan.md` | BLOCKED |
| AI callers and picker effects | TBD; enumerate every integrated `BackendAiRepository` call and sensitive-picker launch | `rg` actual integrated AI-01 source | BLOCKED |
| old-entry personal mutation owners | TBD; verify expected UI/repository list in plan T-001 against integrated source | `rg` all UI-reachable insert/update/delete calls and their transaction owner | BLOCKED |

T-001 must explicitly answer whether the integrated ownership row already has a stable dataset identity. If it does, reuse its exact semantics. If it does not, T-002 adds one field to that row plus the sole handwritten `N -> N+1` migration; it must not add another ownership table.

## Task status

| Task | Owner | Depends | Status | Evidence / blockers |
|---|---|---|---|---|
| T-001 dependency/API inventory | one Android writer | accepted dependencies integrated | BLOCKED | record exact symbols, N and accepted test evidence above |
| T-002 Room dataset access/identity | same writer, sole Room owner | T-001 | BLOCKED | no schema edit before N/API inventory |
| T-003 app gate/account/navigation | same writer | T-002 | BLOCKED | dataset flow required |
| T-004 unified AI eligibility | same writer | T-001–T-003, AI-01 Android | BLOCKED | actual SyncReady API required |
| T-005 settings/cross-feature regressions | same writer | T-003,T-004 | BLOCKED | production diff must be stable |
| T-006 version and Gate I | same writer | T-002–T-005 | BLOCKED | one version bump only |
| T-007 independent Gate T/V | tester + strict reviewer | stable T-006 | BLOCKED | tester is sole Gradle owner |
| T-008 consolidated fixes | original writer | T-007 findings | BLOCKED | only if needed |
| T-009 root full gates | root | T-007/T-008 | BLOCKED | stable final diff only |

## AC-to-test checklist

| AC | Mandatory evidence | Status |
|---|---|---|
| AC-001 | fresh guest offline start/finish/history; restart and active-workout preservation | PENDING |
| AC-002 | guest CAL-01 create/edit/delete; zero Calendar API/scheduler calls without explicit CAL-02 connect | PENDING |
| AC-003 | complete AI eligibility matrix; exercise + InBody; no picker/read/encode/HTTP before Ready; manual paths; backend unauthorized mapping | PENDING |
| AC-004 | persistent Account and blocked-AI registration cards; semantics, 48dp, fontScale 2.0; no repeated/post-workout dialog | PENDING |
| AC-005 | first-value loading; GUEST/CLAIMED/OWNED token matrix; mismatch zero mutation; dataset ID migration/stability/rotation; invalid-state fail closed | PENDING |
| AC-006 | authenticated remains CLAIMED through failures/batches until final ACK; zero Google I/O; login and process death never replay AI | PENDING |
| AC-007 | same-dataset route preservation; replacement resets; restored/missing detail and late old-dataset action rejected; active workout retained | PENDING |

## Required race and recovery vectors

- Cold Room ownership flow: token present but first ownership value delayed; only loading is rendered.
- `CLAIMED(B)` with absent/expired B token and with C token: local dataset remains readable, recovery targets B, zero C mutation/network apply.
- `OWNED(A)` with absent token and with B token: A remains the displayed owner; B cannot claim/render until the accepted preflight/transition commits.
- Crash/cancellation before and after accepted dataset replacement transaction: old ID or new ID is complete; never mixed ownership/navigation.
- Same dataset sign-out/re-auth and `GUEST -> CLAIMED -> OWNED`: route, back stack and active workout survive despite session/sync-generation changes.
- Dataset A route/dialog/AI response arrives after replacement by B: captured identity/epoch fails; detail row is not read or rendered for B.
- Dataset A editor suspends immediately before a local insert/update/delete, replacement commits B, then A resumes: old NavBackStackEntry VM is cleared/cancelled and the same-transaction dataset guard proves zero write into B.
- Exercise/InBody eligibility becomes blocked after a suspension: no picker effect/request or response application; `CancellationException` is rethrown.
- Login succeeds, guest merge remains pending or process recreates: account status is truthful and no saved AI command/attachment runs.
- Registration and guest calendar CRUD with Calendar fake: no Google API call, CAL-02 enqueue, cursor, journal or link mutation.

## Gates and decisions log

- 10.09.2026 independent Sol/high narrow recheck: PASS, no P0/P1/P2. Durable identity extends the existing ownership row; `BackendSync` rotates it only with committed replacement. Old navigation ViewModelStores/jobs are cleared, and late writes use a same-transaction dataset guard. T-007 is targeted only; root full gates remain T-009. No code or Gradle was run for this review.

- No implementation starts until Gate P PASS and every T-001 dependency row is resolved from source.
- Any missing durable dataset identity is implemented in the existing guest-sync owner row by the same Room writer; no new table or ordinary sync-generation key is accepted.
- T-001 records every actual UI-reachable personal mutation owner; T-003 owns those exact files and does not rely on VM cancellation where a DAO write may already have been queued.
- Tokens never select or label local data. Room ownership plus dataset ID gates rendering; existing guest-sync gates all network mutation.
- Account navigation is explicit. Login restores the same local screen only while dataset ID is unchanged and never preserves an executable AI intent.
- Registration performs no Google I/O; only the accepted explicit CAL-02 connection flow may do so.
- No screenshots, real provider calls, backend deployment, Git operation or Gradle run occurs during this documentation stage.
