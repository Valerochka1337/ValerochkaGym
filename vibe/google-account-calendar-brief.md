# Feature Brief — stage 08 / #43: Google account preference and explicit Calendar connection

## Status

pass

## Scope and non-goals

Stage 08 records the Google identity selected during a successful backend Google sign-in as the
preferred Calendar account, and makes Calendar access a separate explicit connection in Settings.
It supports selecting a different Calendar account and safely disconnecting a connected account.

It does not implement CAL-01 local plans or modify its frozen legacy schedule payload; initial
upload/backfill, remote event reading, sync tokens, exceptions, background Calendar work and
external-event import remain CAL-02 work. Calendar owner/link metadata is local-only and must not
be sent through `PortableData`, backend sync, or the frozen CAL-01 contract.

## Candidate acceptance criteria

- **AC-001:** A successful `AccountViewModel.google` backend sign-in saves a normalized preferred
  Calendar email only after backend acceptance. It requests no `calendar.events` grant, schedules
  no Calendar worker, and creates/uploads no Calendar events or plans.
- **AC-002:** Password registration, verification and password login leave Calendar preference and
  Calendar connection unchanged.
- **AC-003:** Settings’ **Подключить календарь** authorizes only the preferred account with
  `calendar.events`, and commits the connected account only after `Granted`. Cancellation or error
  preserves the prior connection and every local link.
- **AC-004:** **Другой аккаунт** explicitly opens a Credential Manager picker, records the selected
  email as the new preference, then explicitly connects that exact account. A→B never lets B alter
  or delete an A-owned event.
- **AC-005:** **Отключить календарь** revokes only the connected account’s requested
  `calendar.events` scope, clears local connection state only after revocation succeeds, does not
  clear backend login, and never deletes local records or remote events.
- **AC-006:** New one-off event links carry immutable local owner metadata. Existing ownerless rows
  remain quarantined: they are retained and visible but cannot be remotely changed, deleted,
  adopted or exported by automatic logic.
- **AC-007:** Process recreation retains preference, connection, local owner metadata and existing
  schedule recovery semantics. Missing, different or externally-revoked accounts leave linked work
  paused/failed safely; they never cause a mutation under another account.
- **AC-008:** Connection controls expose label/state to TalkBack, meet 48dp targets, work at
  `fontScale = 2`, use `GymHaptics` and disable while their one action is running.

## Current execution and data flow

1. `AccountViewModel.google` obtains `GoogleIdTokenCredential`, posts only its ID token and nonce
   to `/auth/google`, then `accept` stores backend tokens and enqueues backend sync
   (`ui/account/AccountViewModel.kt:57-61`, `110-135`). The credential email is currently not
   retained.
2. Settings currently conflates identity choice with consent: `signIn` calls `GoogleAuth.signIn`,
   then immediately `requestAuthorize` (`ui/settings/SettingsViewModel.kt:247-261`).
   `GoogleAuthManager.signIn` selects a Credential Manager account and writes `google_email`
   (`data/google/GoogleAuthManager.kt:54-77`), while `authorize` reads that key and passes it to
   `AuthorizationRequest.setAccount` (`79-99`, `135-151`).
3. The present Connections UI offers only **Войти через Google** or **Выйти**
   (`ui/settings/SettingsScreen.kt:348-390`). `GymSettings.googleEmail` is one DataStore value
   (`data/settings/SettingsRepository.kt:21-23`, `66-90`, `122-125`).
4. One-off Calendar writes call unbound `GoogleAuth.getAccessToken`, then persist only a remote
   event id (`data/google/CalendarRepository.kt:53-140`; `ScheduledWorkoutEntity.kt:21-26`). Thus
   those links currently have no account owner.
5. The old weekly schedule has already solved its separate local ownership problem:
   `WeeklySchedule.ownerEmail` (`data/schedule/WeeklySchedule.kt:28-30`) is checked before API
   work and the durable journal requests a token for the exact account
   (`WeeklyScheduleRepository.kt:93-119`, `242-249`, `384-408`). Its payload and contract remain
   frozen for CAL-01.

## Recommended local-only design

- Add distinct, normalized DataStore fields for `preferredCalendarEmail` and
  `connectedCalendarEmail`. Keep the existing `google_email` value as the legacy weekly-schedule
  connection identity until all existing readers are deliberately migrated; do not redefine it as a
  merely preferred account.
- Add a local Room table, for example `calendar_event_account_links`, keyed by the existing
  `scheduled_workouts.id`, with `ownerEmail` and an explicit `state` (`OWNED` or
  `LEGACY_OWNER_UNKNOWN`). This isolates authorization linkage from `ScheduledWorkoutEntity` and
  avoids adding Calendar identity fields to any portable DTO. A foreign key with cascade cleanup
  keeps a removed local workout from leaving a link row behind. New rows are written in the same
  Room transaction as the one-off event link after a successful insert.
- The migration creates no invented owner. All pre-existing links receive
  `LEGACY_OWNER_UNKNOWN`; no DataStore-dependent lazy adoption is allowed because a later account
  switch makes that attribution unknowable. Preserve these records through existing import paths as
  ownerless local records; importing/exporting `PortableData` neither reads nor writes the metadata
  table. It is authorization state, not training data.
- Calendar operations resolve their account from local metadata. They require the currently
  connected email to equal the immutable `OWNED` email and use
  `AccountBoundGoogleAuth.getAccessTokenForAccount(owner)`. A mismatch, absence, revocation or
  unknown legacy owner performs no remote request and retains local data.
- After a backend Google credential succeeds, `AccountViewModel` writes the preferred email but
  does not call `GoogleAuth.authorize`, Calendar repositories or a Calendar scheduler. Email/password
  flows leave it unchanged.
- **Подключить календарь** calls authorization with the preferred account. **Другой аккаунт** first
  uses Credential Manager to obtain an email, persists it as preferred, and then calls an explicit
  account-bound authorization. `AuthorizeOutcome.Granted` is the only point at which connected
  state changes and existing weekly recovery may be woken. The authorization result has no account
  identity, so this preceding credential selection is required.
- **Отключить календарь** builds `RevokeAccessRequest` with both
  `setAccount(Account(connectedEmail, "com.google"))` and the precise
  `calendar.events` scope, awaits `AuthorizationClient.revokeAccess`, then clears only the local
  connected state on success. It must not call `BackendSync.signOut`, `AccountViewModel.logout`,
  or `CredentialManager.clearCredentialState`; those concern application login/default credential
  selection, not Calendar disconnection. Revoke failure retains the connection for retry.

## Affected files and layers

- `ui/account/AccountViewModel.kt` and its test: save the backend-approved credential email as a
  preference through an injected narrow seam.
- `data/settings/SettingsRepository.kt`: explicit preferred/connected values and atomic clear/set
  operations; compatibility with current `google_email` readers must be staged deliberately.
- `data/google/GoogleAuth.kt`, `GoogleAuthManager.kt`, `di/GoogleModule.kt`: separate select,
  account-bound authorize, token and targeted revoke seams. The existing
  `AccountBoundGoogleAuth` is the correct production seam.
- `ui/settings/SettingsViewModel.kt`, `SettingsScreen.kt`: state distinction, pending consent,
  single busy gate and accessible controls.
- `data/google/CalendarRepository.kt`, Calendar UI call sites and tests: account-gated one-off
  operations.
- `data/db/GymDatabase.kt`, a new entity/DAO and `app/schemas/`: a manual Room migration for the
  local-only link table. No destructive fallback.
- `data/backend/PortableData.kt`: verify it remains unchanged with respect to Calendar
  owner/authorization data. Add regression tests proving local metadata does not cross this wire.
- `data/schedule/WeeklyScheduleRepository.kt`, recovery worker/scheduler tests: preserve existing
  `ownerEmail` and journal behavior; do not change the frozen payload or CAL-01 contract.

## Project invariants

- One `:app` module, Hilt/KSP, no new module or dependency.
- Room uses a hand-written migration plus exported schema; destructive fallback is forbidden.
- UI strings stay in Kotlin; colors use `MaterialTheme.colorScheme`, motion uses `GymMotion`, and
  haptics use `GymHaptics`.
- Cancellation propagates; no `Log.*`. Existing `stateIn(WhileSubscribed)` tests require a live
  collector.
- Local Calendar authorization metadata is device-local and does not become backend portable data;
  backend account login remains independent from a Calendar OAuth grant.

## Android-specific risks and verification

- Google’s authorization result supplies a token but not the selected account identity. Always
  establish/record identity through Credential Manager before requesting its authorization.
- `AuthorizationRequest.Prompt.SELECT_ACCOUNT` overrides `setAccount`; do not combine it with the
  preferred-account connect. Use the separate picker for **Другой аккаунт**.
- `RevokeAccessRequest.Builder` supports both `setAccount` and `setScopes`. Supplying both is the
  narrowest documented request. However, `AuthorizationClient.revokeAccess` documents that future
  sign-in or authorization requires consent to all requested scopes; do not promise that it leaves
  every Google sign-in relationship or every previously granted scope untouched. The app must keep
  backend session tokens intact and make a later Google backend sign-in reauthenticate normally.
- External revocation, expiry or account removal is indistinguishable from an unavailable grant
  until token acquisition/API failure. Retain local links and show recoverable error; do not infer
  absence of a plan or mutate under another account.
- No Android Calendar-provider permission is needed: the integration uses OAuth plus Retrofit, not
  `CalendarContract`. No stage-08 worker should issue Calendar writes.

Recommended test surfaces: `AccountViewModelTest`; `SettingsViewModelTest`, Settings Compose tests
and `SettingsRecoverySchedulingTest`; `GoogleAuthManagerTest` (including revoke request account and
scope); `CalendarRepositoryTest`; a Robolectric Room migration/DAO test for owned and quarantined
links; `PortableData` tests proving the table stays local; existing weekly schedule and journal
tests for A→B/missing/revoked identity. Use handwritten fakes, `MainDispatcherRule`, `runTest` and
`collectUiState` patterns already established by the repository.

## Official sources

- [Authorize access to Google user data](https://developer.android.com/identity/authorization)
  — authentication/authorization separation, authorization result identity limit, default and
  alternate-account behavior.
- [AuthorizationRequest.Builder](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationRequest.Builder)
  — `setAccount` and `SELECT_ACCOUNT` override.
- [RevokeAccessRequest.Builder](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/RevokeAccessRequest.Builder)
  — account- and scope-targeted revocation request construction.
- [AuthorizationClient](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationClient)
  — revoke semantics and consent requirement after revocation.
- [Implement Sign in with Google](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation)
  — Credential Manager account selection and backend ID-token verification.

## Files changed

- `vibe/google-account-calendar-brief.md`
