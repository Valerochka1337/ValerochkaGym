# Coach relations — Android client

## Goal, scope and frozen boundary

Deliver the Android client for the accepted backend coach-relation contract: authenticated users create or explicitly accept a one-time invitation, manage two independent grants, revoke either side, and read only the authorized calendar/completed-workout projections. A later, separate adapter lets the completed PLAN-01 client author/revise/revoke a coach proposal through its selected relation.

Scope is exactly [coach-relations-brief.md](coach-relations-brief.md) AC-001…008 and backend fixture `coach-relations-contract.json`, accepted SHA-256 `f5960d8a8fd269518aa6347b18607a778e4eff7f64aa5ecd9a2dc529aa501460`. Non-goals: social search, names/emails/profile lookup, message/share side effects, active workouts, notes/hints, profile, health, measurements, AI context, owner sync/`PortableData`, offline read cache, automatic proposal application, and resolving PLAN-01 edited-preview equality.

`POST/GET /v1/coach-relations` routes are a dedicated server projection boundary, never `BackendSync` or `/records`. Room is SSOT only for mutation recovery. Directory and projection pages are in-memory ViewModel state: an owner/relation/revision change drops the page; pages from different actor/relation/revision never merge. Only `invalid_cursor` (400), `cursor_expired`/`cursor_key_retired` (410), or `relation_snapshot_changed` (409) restart once from the first cursor for that same actor/mode/relation. `relation_not_found` (404) is terminal denied/revoked: clear the page and capability and stop, never paginate/restart.

## Acceptance criteria

| ID | Criterion |
|---|---|
| AC-001 | Create shows a transient one-time invite token; accept requires explicit calendar and completed-workout grants, has no account lookup, and preserves server expiry/single-use/replay behavior. |
| AC-002 | Directory/relation reads and separate projections reveal only the current actor's authorized relation/grant data. |
| AC-003 | Completed projection renders only server allowlisted completed exercise/actual-set facts; excluded data never enters Android models/state. |
| AC-004 | After PLAN-01 client delivery, coach authoring uses the relation-scoped endpoint; recipient approval remains PLAN-01's authoritative-sync path. |
| AC-005 | Either participant can revoke; current UI immediately loses capability and pending coach actions fail closed, while accepted local history remains untouched. |
| AC-006 | Exact mutation recovery, expiry/replay/conflict, directory/projection revision restart, cancellation and recreation create no duplicate intent or revived capability. |
| AC-007 | Owner switch, logout, late response and process death cannot expose/transfer another owner's pages or mutation result; no guest auto-claim exists. |
| AC-008 | Account/Settings entry and pushed relation/detail/consent states cover loading, empty, pending, active, revoked and error, with 48dp actions, TalkBack, fontScale 2.0 and adaptive layout. |

## Frozen contracts and flow

```text
Account/Settings → CoachRelationsViewModel event → CoachRelationsRepository
  → exact mutation journal transaction → authenticated backend route
  → same-owner result guarded by operation/route/resource/hash → immutable StateFlow
```

Routes and DTOs are fixture-defined: create `{operationId}` returns `{inviteId, token, expiresAtMillis}`; accept `{operationId, token, calendar, completedWorkouts}`; revoke `{operationId}`; directory returns only `relationId`, `counterpartyId`, `state`, grants and times; calendar/completed pages use only their fixture allowlists and cursor/revision. Android displays a neutral “Participant” plus `counterpartyId`; it never invents a name/email or performs lookup. The projection vectors must preserve empty arrays, 200 exercises, 1,000 sets, zero values, explicit actual-null winning over any planned fallback, and a singleton response exceeding 1 MiB rejected with 413. Request bodies use the frozen strict JSON shapes. API errors distinguish terminal 404 capability denial from 409 operation/revision conflict and the narrowly restartable cursor errors without exposing account existence.

The invite token is transient input/result only: never SavedStateHandle, Room, DataStore, logs, analytics, exception text or navigation argument. It is not retried after process death; the user may re-enter it, and server semantics decide safe existing-active/used/expired outcomes. Create output is shown/copiable only through the existing local UI action; the app sends no message or share action automatically.

`CoachRelationOperationEntity` is the only durable local aggregate: owner ID, operation UUID, action, normalized route/resource tuple, raw SHA-256, nullable `firstSendBytes`, state and sanitized terminal result. Before POST it atomically writes the exact UTF-8 first-send bytes for normal revoke and relation-scoped proposal create/revise/revoke; retry replays those bytes literally. Its tuple/hash prevents a changed route, resource or body from reaching network. `CREATE_INVITE` and `ACCEPT_INVITE` strictly store null bytes plus hash/sanitized metadata: no invite token, raw accept body, create token or projection is durable. Accept cannot replay after process death; the user may re-enter the token and server semantics decide safe existing-active/used/expired outcomes. Create persists no returned token, so a lost result is terminally recoverable only by creating a new invitation. Repository owns injected IO and cancellation, rethrows `CancellationException`, and checks owner/token before applying a result.

Room version is the actual integrated future `N`, resolved after CAL-01 and preceding AI/notes/profile/health/proposal migrations. No relation read cache is added. The deliberate operation journal has one Android writer for entity, DAO, migration, database version, exported schema and incremental/full migration tests together. Hilt binds one application-scoped repository. ViewModels use immutable state/events, `stateIn(WhileSubscribed(5000))`, lifecycle collection, and SavedStateHandle only for route/relation ID—not token, pages or grants-in-progress.

## Tasks and ownership

| ID | Exact files / owner | Depends | Action | Automated verification | Done condition | AC |
|---|---|---|---|---|---|---|
| T-001 | Contract owner: this plan/tracker; verify backend `vibe/contracts/coach-relations-contract.json` SHA and copy exact fixture to `app/src/test/resources/coach-relations-contract.json` only when Android implementation begins | accepted backend fixture; CAL-01 contract | Freeze Android DTO field allowlists, endpoint/error/revision behavior, exact journal sanitation and PLAN-01 handoff against accepted backend SHA. Verify vectors for empty arrays, 200 exercises, 1,000 sets, zero values, actual-null over planned fallback, and singleton >1 MiB 413. No local `vibe/contracts` copy is needed while backend fixture is canonical/readable. | SHA-256 parity, JSON duplicate-key/schema/vector parse | Byte-identical Android fixture and no ambiguous token/replay/paging/projection behavior. | AC-001…006 |
| T-002 | Sole Android Room/API writer: `data/coachrelation/{CoachRelationsApi.kt,CoachRelationModels.kt,CoachRelationsRepository.kt,CoachRelationOperationStore.kt}`, `data/backend/{BackendApi.kt,BackendModels.kt}`, `data/db/{GymDatabase.kt,Migrations.kt,entity/CoachRelationOperationEntity.kt,dao/CoachRelationOperationDao.kt}`, `di/{BackendModule.kt,DataModule.kt}`, `app/schemas/.../N+1.json`; matching `data/coachrelation/*Test.kt`, `data/db/MigrationNToNPlus1Test.kt`, `Migration1ToNPlus1Test.kt` | T-001; stable actual N | Implement strict DTO parsing/allowlists and authenticated routes. Add only the exact journal: literal `firstSendBytes` before POST for revoke/proposal mutations; null bytes/hash-only sanitization for create/accept; handwritten migration/schema and owner guards. No projection cache, sync table or token persistence. | focused fixture/API/repository/DAO/migration tests; `./gradlew :app:compileDebugKotlin` | Owner A/B, guest, changed route/resource/body, response loss, cancellation/recreation, literal safe replay, 404 terminal-clear and no-token-storage tests pass. | AC-001,002,003,005,006,007 |
| T-003 | Same writer: `ui/coachrelation/{CoachRelationsViewModel.kt,CoachRelationsScreen.kt,CoachRelationDetailScreen.kt,CoachRelationModels.kt}`, `ui/{account/AccountScreen.kt,settings/SettingsScreen.kt,navigation/GymNavGraph.kt}`, focused VM/Compose tests | T-002 | Add an authenticated Account/Settings entry and pushed directory/detail/accept flow. Expose explicit two-grant consent and revoke; show token transiently with an explicit local copy action only. Render neutral counterparty ID and server allowlists; no name/email lookup. | focused ViewModel/Compose semantics/adaptive/recreation tests; compile | All required visible states and denied/revision-restart paths are reachable and accessible. | AC-001,002,003,005,006,007,008 |
| T-004 | Same writer: `data/coachrelation/*`, `ui/coachrelation/*`, `ui/trainingproposal/*` only after PLAN-01 T-003…005, `ui/navigation/GymNavGraph.kt`, focused proposal/relation tests | T-003; completed PLAN-01 Android client | Reuse the exact journal for relation-scoped proposal create/revise/revoke, including literal lost-response/recreation retry and changed route/resource/body denial. Do not implement recipient apply here: it remains PLAN-01's persisted request then authoritative `BackendSync` flow. Revocation/404 removes authoring availability without altering accepted routines/history. | focused relation/proposal owner/revoke/late-response/recreation/conflict tests; compile | Coach authoring cannot bypass a live relation; recipient behavior remains PLAN-01. | AC-004,005,006,007,008 |
| T-005 | Same writer: `app/build.gradle.kts`, coach tracker | T-003,T-004 | Compare target version, apply one code/patch increment and record actual Room N/fixture SHA/commands. | `./gradlew :app:compileDebugKotlin` | One relation feature increment; no dependency/catalog change. | AC-001…008 |
| T-006 | Independent Android tester + read-only reviewer; tracker findings | stable T-005 | Audit token non-retention, mutation recovery, grant/allowlist/privacy, revision paging, owner/death/revoke and UI semantics. | missing focused tests only; reviewer no Gradle | Consolidated Gate T/V has no P0/P1. | AC-001…008 |
| T-007 | Original writer, only confirmed T-002…005 files; tracker | T-006 findings | One bounded repair and narrow recheck. | smallest invalidated command | No P0/P1; any P2 is explicit. | affected |
| T-008 | Root integration owner; tracker | T-006/T-007 | Run final stable Android gates. | `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug` | Every AC has command/result evidence. | AC-001…008 |

Execution is serial: T-001 → T-002 → T-003 → T-004 → T-005; T-006 review/test after a stable diff; conditional T-007; T-008. One writer owns every mutable Android path, including Room, API, DI, navigation and version choke points.

## Gates, risks and rollback

Relevant gates: fixture parity/strict parsing; Room incremental/full migration only if the deliberate operation journal is added; owner/guest/claim/A→B/late-result/cancellation/recreation; exact operation and token non-retention; directory/projection cursor/revision restart; server-field allowlists; navigation/back/restoration; TalkBack, 48dp, fontScale 2.0 and compact/medium/expanded UI. No WorkManager, service, permission, chart, screenshot, sync worker, dependency or release gate is introduced.

Forward rollback keeps the sanitized operation journal but clears in-memory pages on owner change/revoke; it never restores a capability, replay token, or modifies accepted routines/history. A backend revision change forces restart, never merged paging. The edited-preview decision is explicitly approved and its backend is accepted locally. T-004 waits for the completed PLAN-01 Android client; recipient approval remains owned by that client.

## Gate P self-check

AC-001…008 each map to a task and automated evidence. The backend fixture/routes and privacy allowlists are frozen; invitation tokens have no durable path; Room scope is limited to exact mutation recovery; shared Android choke points have one writer; and PLAN-01 ownership remains separate. Recommended first implementation task after fixture parity is T-002.
