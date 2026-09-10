# Plan: Google account preference and explicit Calendar connection (#43, stage 08)

## Goal, scope, and non-goals

Record a backend-approved Google identity as a preferred Calendar account, then let Settings
explicitly connect, change, or narrowly disconnect Calendar access. Bind new one-off Calendar links
to that account locally so no account can mutate another account’s event.

This stage does not implement CAL-01 local plans, Calendar upload/backfill/read/sync tokens,
exceptions, worker writes, or external-event import. It never changes the frozen legacy weekly
schedule wire payload/ownership, transfers OAuth ownership, adds local owner fields to portable
DTOs, or signs out backend login during Calendar disconnect.

## Acceptance criteria

| ID | Criterion |
|---|---|
| AC-001 | Backend-accepted Google sign-in saves normalized preferred Calendar email only after acceptance, with no Calendar grant, worker, event, plan, or upload side effect. |
| AC-002 | Password registration, verification, and password login leave preferred/connected Calendar state unchanged. |
| AC-003 | Connect authorizes only preferred account for `calendar.events`; connected state changes only after that exact account's noninteractive token success (`no resolution` and non-null token), while cancellation/error retains prior connection and links. |
| AC-004 | Other account explicitly picks an identity, records it preferred, then explicitly connects that exact account; A→B cannot mutate/delete A-owned event. |
| AC-005 | Disconnect revokes only connected account’s `calendar.events`, clears connection only after success, retains local/remote records and backend login. |
| AC-006 | New links have immutable local owner metadata; existing ownerless rows are visible but quarantined from remote mutation, deletion, adoption, and export. |
| AC-007 | Recreation retains preference, connection, owner metadata and weekly recovery semantics; missing/different/revoked account safely pauses/fails linked work without foreign mutation. |
| AC-008 | Connection controls have TalkBack label/state, 48dp targets, GymHaptics, large-text/adaptive behavior, and a one-action busy gate. |

## Current → target flow

**Current:** backend Google sign-in drops credential email; Settings conflates picker and
authorization through legacy `google_email`; one-off Calendar operations take an unbound token and
store only event id. Existing weekly schedule independently uses `ownerEmail` and its frozen legacy
DataStore/journal contract.

**Target:** backend Google credential → backend accepts → narrow identity seam stores normalized
preferred email. Settings connect reads preferred → account-bound `calendar.events` authorization →
Only account-bound noninteractive token success (no resolution and non-null token) atomically records
connected email. Other account selects identity first then repeats the same bound authorization. One-off Calendar create transaction stores scheduled row plus `OWNED`
local link; existing event rows receive `LEGACY_OWNER_UNKNOWN`. Calendar mutation resolves link owner
and requires connected == owner before token/API call. Disconnect builds targeted revoke, then clears
only connected identity on success. Room/DataStore remain SSOT; CAL-01 legacy payload is untouched.

## Frozen contracts and decisions

- `CalendarAccountIdentity` is the narrow injected test seam. It normalizes email with
  `trim().lowercase(Locale.ROOT)`, exposes distinct preferred/connected state, and provides atomic
  set/clear operations. `SettingsRepository` implements it using new DataStore keys;
  legacy `google_email` may seed a preferred-account candidate during migration only; it is never a
  Calendar authorization identity and is neither redefined nor cleared here.
- `AccountViewModel.google` receives the credential email but calls `setPreferred` only after
  `/auth/google` accepts backend credentials. Password paths never call the seam. No Calendar auth,
  scheduler, repository, or event code is reachable from this success path.
- `GoogleAuth` gains explicit select-account, authorize-for-account and revoke-calendar-access
  contracts; `AccountBoundGoogleAuth.getAccessTokenForAccount(owner)` remains token seam.
  Preferred connect uses `setAccount` without `SELECT_ACCOUNT`; Other account uses Credential
  Manager first, persists selected normalized identity, then authorizes it. Connected state commits
  only after that exact account's noninteractive token success: no resolution and non-null token.
  NeedsConsent/failure preserve the previous connection exactly (unset remains unset). Existing A stays connected while switching to B through consent, cancellation, process recreation and errors; only verified non-null token success for B atomically replaces A. One busy state serializes picker/authorize/revoke;
  cancellation rethrows and leaves previous state intact.
- Revoke builds `RevokeAccessRequest` with `Account(connectedEmail, "com.google")` and only
  `calendar.events`, awaits success, then clears connected state. It does not call backend sign-out,
  account logout, credential-state clear, link deletion, or remote-event deletion. Revoke failure
  retains connection for retry.
- Room v16→v17 creates `calendar_event_account_links` keyed by `scheduled_workouts.id`, FK cascade,
  immutable normalized `ownerEmail` for `OWNED`, and explicit `LEGACY_OWNER_UNKNOWN`. The handwritten
  migration creates no inferred owner and backfills every existing scheduled row as unknown. Entity,
  DAO, migration, `GymDatabase` registration/version, exported `app/schemas/.../17.json`, and all
  migration tests have one owner. No destructive fallback.
- Calendar create writes event remotely then writes schedule+owned link in one Room transaction.
  Cancel/create/token operations first resolve local link: only `OWNED(owner)` with current connected
  owner equal to owner can acquire an account-bound token/call API. Unknown/missing/different/revoked
  links retain local row and return recoverable unavailable state with no remote request. Links are
  device-local authorization data: `PortableData`, backend sync and frozen CAL-01 legacy payload
  neither read nor write them; no lazy adoption/import export is allowed.
- Settings saves only primitive pending consent kind, normalized target, opaque operation-correlation generation nonce (called token) and busy state in
  `SavedStateHandle`. The matching `RESULT_OK` reauthorizes that saved target; cancel clears
  pending/busy while retaining connection; stale or concurrent replies are ignored. Recreation
  restores/reissues exact pending auth, never the current preferred identity.
- `WeeklyScheduleRepository` interactive/recovery gates migrate from `google_email` to
  `connectedCalendarEmail`. Wake runs only when connected equals active/journal owner; legacy A
  cannot mutate while B connects. Disconnect/missing/revoked identity pauses/fails with zero API
  calls. No one-off link owner is inferred; stage 08 adds no Calendar worker.

## Tasks

| ID | Exact files | Owner | Depends on | Actions | Automated verification | Done condition | AC |
|---|---|---|---|---|---|---|---|
| T-001 | `data/settings/CalendarAccountIdentity.kt`, `data/settings/SettingsRepository.kt`, `data/google/GoogleAuth.kt`, `data/google/GoogleAuthManager.kt`, `di/GoogleModule.kt`, `ui/account/AccountViewModel.kt` and their `GoogleAuthManagerTest`, `AccountViewModelTest`, `CalendarAccountIdentityTest`. | implementation writer | — | Add injected account-identity seam; legacy `google_email` seeds only a preferred candidate; persist connection only after account-bound, noninteractive token success. `Granted` requires no resolution and non-null token. Typed account/scope revoke never signs out backend. | `./gradlew :app:testDebugUnitTest --tests "*GoogleAuthManagerTest" --tests "*AccountViewModelTest" --tests "*CalendarAccountIdentityTest"` | Backend/password paths preserve Calendar state; A→B consent/cancel/recreation/failure tests retain A; only verified B success replaces it. | AC-001–AC-005, AC-007 |
| T-002 | `data/db/entity/CalendarEventAccountLinkEntity.kt`, `data/db/dao/CalendarEventAccountLinkDao.kt`, `data/db/GymDatabase.kt`, `data/google/CalendarRepository.kt`, schema `app/schemas/com.valerochka1337.valerochkagym.data.db.GymDatabase/17.json`, tests `data/CalendarRepositoryTest.kt`, `data/CalendarEventAccountLinkDaoTest.kt`, `data/db/Migration16To17Test.kt`, `data/db/Migration1To17Test.kt`, `data/PortableDataTest.kt`. | implementation writer | T-001 | Sole Room owner implements local immutable owner links, hand-written v16→17 and 1→17 migrations, and account-gated transactions. Never infer a one-off owner or add owner metadata to a wire payload. | `./gradlew :app:testDebugUnitTest --tests "*CalendarRepositoryTest" --tests "*CalendarEventAccountLinkDaoTest" --tests "*Migration16To17Test" --tests "*Migration1To17Test" --tests "*PortableDataTest"` | Both migration paths preserve ownerless rows as quarantined and keep links device-local. | AC-003–AC-007 |
| T-003 | `data/schedule/WeeklyScheduleRepository.kt`, `worker/WeeklyScheduleRecoveryWorker.kt`, `data/WeeklyScheduleRepositoryTest.kt`, `worker/WeeklyScheduleRecoveryWorkerTest.kt`. | implementation writer | T-001–T-002 | Change every interactive and recovery gate to `connectedCalendarEmail`; wake only when it equals the active/journal owner. Cover A connected then B, disconnect, missing and revoked account, each with zero Calendar API calls when unmatched. | `./gradlew :app:testDebugUnitTest --tests "*WeeklyScheduleRepositoryTest" --tests "*WeeklyScheduleRecoveryWorkerTest"` | Legacy A cannot mutate A work while B is connected. | AC-004, AC-005, AC-007 |
| T-004 | `ui/settings/SettingsViewModel.kt`, `ui/settings/SettingsScreen.kt`, `ui/SettingsViewModelTest.kt`, `ui/settings/SettingsScreenTest.kt`, `ui/SettingsRecoverySchedulingTest.kt`, `app/build.gradle.kts` and feature tracker. | implementation writer | T-003 | Apply exactly one next app versionCode/patch increment above integrated stages and verify compile/spotless. Persist only pending kind, normalized target, token, and busy. Never store OAuth access tokens, credentials or PendingIntent in SavedStateHandle. Test A→B retaining A through consent/cancel/recreation/failure. Keep busy through consent; `RESULT_OK` reauthorizes the saved target; cancel clears pending/busy while retaining connection; stale/concurrent reply is ignored; recreation reissues exact pending authorization. | `./gradlew :app:testDebugUnitTest --tests "*SettingsViewModelTest" --tests "*SettingsScreenTest" --tests "*SettingsRecoverySchedulingTest"` | Only the saved target can commit; recreation/cancel/stale/concurrent cases pass and controls remain accessible. | AC-003, AC-004, AC-008 |
| T-005 | *(no file edits; report to root)* | tester + readonly Sol/high reviewer | T-004 | Parallel strict audit, then one writer fix batch and reviewer recheck. | Smallest invalidated targeted command; read-only diff review. | No P0/P1. | AC-001–AC-008 |
| T-006 | `vibe/google-account-calendar-plan-track.md` | root session | T-005 | Record stable final evidence. | `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug`. | All ACs evidenced. | AC-001–AC-008 |

## Ownership and execution waves

| Owner | Exclusive responsibility |
|---|---|
| implementation writer | T-001–T-004 all production/test changes, including sole Room entity/DAO/migration/schema ownership and shared Google/settings UI choke points. |
| independent tester / readonly Sol-high reviewer | T-005 only; no edits. |
| root session | T-006 final gates and tracker completion. |

One writer is mandatory: identity/auth, settings, Calendar repository and Room contract overlap. Preserve
other active-stage edits; do not alter CAL-01 frozen payload. Wave 1 T-001; wave 2 T-002 then
strictly serial T-003 followed by T-004; wave 3 tester+reviewer
parallel and one fix/recheck; wave 4 root final gates.

## Relevant quality gates and risks

- Strict Room: v16→17 incremental and 1→17 full migration; schema commit; FK/cascade/transaction;
  no destructive fallback. Strict identity: normalized preferred/connected/legacy separation,
  grant/cancel/error/revoke, exact account/scope, A→B, external revocation and recreation.
- UI: loading/connected/preferred/error/busy states, cancellation, 48dp, TalkBack state/action,
  fontScale 2.0 and adaptive layouts. No chart/permission/worker/calendar-provider/reverse-sync gate.
- Data: assert metadata table never enters `PortableData`, backend sync, import/export, or CAL-01
  legacy wire JSON; ownerless links are retained/quarantined. No Calendar write worker is added.
- Risks: authorization result lacks identity (picker-before-authorize contract); `RevokeAccess` can
  affect future consent (backend tokens intentionally retained); remote insert then local transaction
  failure can orphan remote event (report recoverable failure, never infer owner/delete remotely).
- No unresolved product blocker. Rollback preserves preferred/connected keys and link table rows;
  code removal never deletes local/remote events or backend session.

## Gate P self-check

Pass: AC-001…AC-008 map to implementation/testing tasks; a single owner covers all overlapping
contracts and the full Room migration set; preferred/connected/legacy ownership, local-only links,
quarantine, revoke and CAL-01 boundaries are frozen; only relevant strict Room/auth/lifecycle/UI,
independent review and final gates are included.
