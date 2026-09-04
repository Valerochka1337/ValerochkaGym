# Relaunch after update — implementation plan

## Goal

After a successfully replaced self-update, make reopening the app reliable by posting a
user-tappable Android notification. Android 16+/target 37 background-activity-launch rules mean a
regular sideload using `PackageInstaller` with `USER_ACTION_REQUIRED` cannot promise an automatic
post-replacement Activity launch; this feature deliberately does not attempt one.

## Scope

- Receive the system-only `ACTION_MY_PACKAGE_REPLACED` broadcast after this package is replaced.
- Persist an idempotent post-update notification marker in the existing Preferences DataStore,
  then issue one stable notification that opens the app through an explicit, immutable
  `PendingIntent`.
- Preserve the existing signed-APK, version, SHA-256, session and user-confirmation flow. Add
  lowest-layer Robolectric/manifest coverage and perform this feature's single version increment.

## Non-goals

- No forced/background launch, `SYSTEM_ALERT_WINDOW`, accessibility workaround, foreground service,
  WorkManager job, new dependency, Room entity/DAO/schema/migration, or updater UI redesign.
- No request for `POST_NOTIFICATIONS` from a receiver. A denied notification permission remains a
  safe no-notification path.

## Assumptions

- The installed build is an ordinary same-signing-key sideload on minSdk 36 / targetSdk 37.
- `POST_NOTIFICATIONS` is already declared and is requested in its existing contextual workout
  flow; it can be denied or notifications can be disabled at channel/app level.
- `MainActivity` remains enabled but intentionally has no launcher filter; exactly one enabled
  activity-alias owns the launcher icon. An explicit in-app `MainActivity` intent is valid and does
  not depend on an alias.

## Acceptance criteria

- **AC-001:** A replacement of this package invokes an `exported=false`, short-lived receiver for
  `Intent.ACTION_MY_PACKAGE_REPLACED`; unrelated broadcasts and the old install-status callback do
  not create a relaunch notification.
- **AC-002:** The receiver records the installed version in a durable DataStore SSOT before/while
  coordinating notification delivery, is idempotent for duplicate delivery, and survives process
  death without relying on `AppUpdateInstallEventBus` or the ViewModel.
- **AC-003:** When notifications are permitted, exactly one stable post-update notification for the
  installed version is posted with understandable title/text and an immutable explicit activity
  `PendingIntent`; tapping it opens the normal app task safely without a background launch. Its
  activity intent includes `NEW_TASK`, `CLEAR_TOP`, and `SINGLE_TOP`.
- **AC-004:** When notifications are denied/disabled or notification posting fails, no component
  crashes or launches UI; the durable marker is not marked delivered and remains eligible for a
  later reconciliation, including after a blocked channel is re-enabled.
- **AC-005:** Existing update security and confirmation contracts remain intact: same package,
  newer version, exact signer, SHA-256, `USER_ACTION_REQUIRED`, private mutable PackageInstaller
  callback, and system confirmation handling.
- **AC-006:** Receiver/coordinator persistence and notification behavior have focused Robolectric
  tests; the manifest proves private receivers and replacement action; this feature increments
  `versionCode` once and the patch `versionName` once.

## Current → target flow

```
Current: PackageInstaller callback → private status receiver → in-memory event bus → ViewModel
         (Succeeded ignored; process replacement destroys the bus)

Target:  package replacement → private ACTION_MY_PACKAGE_REPLACED receiver → coordinator
         → Preferences DataStore (pending/delivered version marker; SSOT) → platform notification
         → user tap → immutable explicit MainActivity PendingIntent → existing normal navigation
```

`AppUpdateInstallStatusReceiver` remains solely responsible for pre-replacement PackageInstaller
statuses and its private mutable callback. The replacement path owns no Compose state or UI event:
the notification is the one-shot Android-system event, while `MainActivity` restores its existing
normal navigation/state on a user tap. The new coordinator uses `@ApplicationScope` (IO) plus
`goAsync()`/`finish()` ownership in the receiver; cancellation is contained at the receiver
boundary, no `GlobalScope` or WorkManager is introduced.

## Architectural decisions and frozen contracts

1. **SSOT and idempotency.** Add a small `PostUpdateRelaunchStore` in `data/update`, backed by the
   already singleton `DataStore<Preferences>`, rather than Room or a static singleton. It atomically
   stores pending and delivered `longVersionCode` values. `recordReplacement(versionCode)` only
   advances to a newer installed version; `claim/markDelivered(versionCode)` makes duplicates
   harmless. A process death after recording but before delivery leaves pending work for
   `reconcilePending()`; a death after `notify()` but before acknowledgement can only replace the
   same fixed notification ID.
2. **Coordinator boundary.** `PostUpdateRelaunchCoordinator` is a `@Singleton`, injected into the
   replacement receiver and `GymApplication`. It owns channel creation, permission/app-notification
   checks, notification construction, fixed channel/notification IDs, and reconciliation. The
   receiver only validates the action, uses `goAsync`, delegates one coroutine to the application
   scope, and always calls `PendingResult.finish()`; it performs no long/blocking work on main.
3. **Safe open contract.** The notification uses `PendingIntent.getActivity` with an explicit
   `Intent(context, MainActivity::class.java)`, `ACTION_MAIN`, `CATEGORY_LAUNCHER`,
   `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP`, and
   `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`.
   It carries no private data and no route; the existing activity and navigation restoration decide
   the screen. The user tap, not receiver code, authorizes activity launch under BAL.
4. **Permission/degraded behavior.** The coordinator creates/reuses its channel and checks
   `POST_NOTIFICATIONS`, app notification availability, and that its dedicated channel importance
   is not `IMPORTANCE_NONE` before `notify`. It calls `markDelivered(versionCode)` only after that
   check and successful `notify`; `SecurityException` and disabled delivery are recoverable no-ops
   that retain pending state. `GymApplication` calls `reconcilePending()` on process startup, so a
   later permission grant, channel re-enable, or transient failure can recover; no receiver requests
   runtime permission or emits a UI error.
5. **DI, threading, persistence.** Reuse `DataModule`'s existing `@ApplicationScope` IO scope and
   singleton Preferences DataStore; bindings for the store/coordinator live in `UpdateModule`, the
   shared Hilt choke point. No `@ComputeDispatcher` computation, Room transaction/migration,
   database schema artifact, background worker, or runtime permission request applies.
6. **UI/accessibility/adaptive layout.** There is no Compose surface. Notification title, text and
   content intent form the accessible system surface; content does not depend on color, motion,
   haptics, screen size, or font scale. Existing update UI states stay unchanged.

## Tasks

### T-001 — Freeze durable post-replacement contract

- **Owner:** Implementation writer (sole owner)
- **Depends on:** —
- **Files:** `app/src/main/java/com/valerochka1337/valerochkagym/data/update/PostUpdateRelaunchStore.kt`
  (new), `app/src/main/java/com/valerochka1337/valerochkagym/data/update/PostUpdateRelaunchCoordinator.kt`
  (new), `app/src/main/java/com/valerochka1337/valerochkagym/di/UpdateModule.kt`
- **Actions:** Implement the atomic Preferences marker API and singleton coordinator contracts above;
  inject the existing DataStore/application scope, create the dedicated channel, build the immutable
  explicit `MainActivity` content intent with `NEW_TASK | CLEAR_TOP | SINGLE_TOP`, guard notification
  permission/app availability/channel importance, and make pending reconciliation idempotent. Only
  acknowledge delivery after a successful post. Keep all text in Kotlin and use platform
  notification APIs already available at minSdk 36.
- **Automated verification:** `./gradlew :app:compileDebugKotlin`
- **Done condition:** The marker/coordinator compiles through Hilt with no new dependency; T-003
  supplies the focused behavior tests.
- **AC:** AC-002, AC-003, AC-004

### T-002 — Wire the private system receiver and startup reconciliation

- **Owner:** Implementation writer (sole owner)
- **Depends on:** T-001
- **Files:** `app/src/main/java/com/valerochka1337/valerochkagym/data/update/AppUpdateReplacementReceiver.kt`
  (new), `app/src/main/AndroidManifest.xml`,
  `app/src/main/java/com/valerochka1337/valerochkagym/GymApplication.kt`
- **Actions:** Declare a separate Hilt receiver with an `ACTION_MY_PACKAGE_REPLACED` filter and
  `exported=false`; use a short `goAsync` handoff to the coordinator and finish exactly once.
  Inject the coordinator into `GymApplication` and reconcile on app process creation. Do not alter
  the existing status receiver's action, mutability or PackageInstaller confirmation path.
- **Automated verification:** `./gradlew :app:processDebugMainManifest`
- **Done condition:** Manifest test finds separate private status/replacement receivers and the
  replacement action; T-003's Robolectric dispatch proves only the system-action path schedules
  handling and startup calls reconciliation without launching an Activity.
- **AC:** AC-001, AC-002, AC-004, AC-005

### T-003 — Add regression tests at the lowest reliable boundary

- **Owner:** Implementation writer (sole owner)
- **Depends on:** T-001, T-002
- **Files:** `app/src/test/java/com/valerochka1337/valerochkagym/data/update/PostUpdateRelaunchTest.kt`
  (new), `app/src/test/java/com/valerochka1337/valerochkagym/data/update/AppUpdateManifestTest.kt`
- **Actions:** Use Robolectric and handwritten fakes only. Cover receiver action filtering,
  exported manifest contract, DataStore persistence/idempotency across recreated collaborators,
  permission-denied/disabled notification no-op, channel-blocked → re-enable → reconcile preserving
  the pending marker until post succeeds, fixed-ID notification content/immutable explicit pending
  intent with all three task flags, and an unchanged install-status callback test. Do not test
  private methods.
- **Automated verification:**
  `./gradlew :app:testDebugUnitTest --tests "*PostUpdateRelaunchTest" --tests "*AppUpdateManifestTest"`
- **Done condition:** All stated paths pass on the pinned sdk=36 Robolectric configuration and each
  test name is a present-tense backtick sentence.
- **AC:** AC-001, AC-002, AC-003, AC-004, AC-005, AC-006

### T-004 — Apply release bookkeeping and final relevant gates

- **Owner:** Implementation writer (sole owner)
- **Depends on:** T-001, T-002, T-003
- **Files:** `app/build.gradle.kts`, `vibe/relaunch-after-update-plan-track.md`
- **Actions:** Increment `versionCode` by exactly one and patch `versionName` by exactly one from
  the current target baseline, record commands/results/deviations in the tracker, and verify no
  other version increment is already part of this feature.
- **Automated verification:** `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug`;
  additionally `./gradlew :app:assembleRelease` when release signing inputs are available (otherwise
  record the precise signing-input blocker).
- **Done condition:** Version pair is exactly one step higher, all applicable commands pass or the
  release signing blocker is recorded, and tracker traceability is complete.
- **AC:** AC-005, AC-006

## File ownership and execution waves

| Owner | Exclusive files/responsibility |
|---|---|
| Implementation writer | Every file in T-001–T-004; DataStore state, coordinator, Hilt, receiver/manifest, application startup, tests, build version, tracker |

One writer is required: `UpdateModule`, `AndroidManifest.xml`, `GymApplication`, and
`app/build.gradle.kts` are shared choke points. **Wave 1:** T-001. **Wave 2:** T-002. **Wave 3:**
T-003, T-004, then final gates sequentially. No parallel implementation.

## Quality gates

- **Always:** AC/task/test traceability; handwritten fakes; no logs, mocks, new dependency, or
  destructive migration; final `:app:testDebugUnitTest` then `:app:assembleDebug` once after stable
  code.
- **System integration/security (applicable):** Robolectric manifest/receiver coverage; verify
  `exported=false`, immutable explicit activity intent with `NEW_TASK | CLEAR_TOP | SINGLE_TOP`,
  deliberately retained mutable private installer callback, notification-denied/channel-blocked
  degraded path, no external data, and no background Activity launch.
- **DI/build/release (applicable):** singleton/application-scope lifetime is explicit; Hilt/KSP
  compiles; version is changed once; run release assembly because manifest/notification behavior is
  release-sensitive if signing inputs permit it.
- **Not applicable:** Room migration/schema gates, WorkManager/foreground-service gates, Compose
  layout/font-scale/navigation visual gates, charts/render gates, and runtime notification-permission
  request UI testing.

## Risks, questions, rollback and data preservation

- **Platform constraint (accepted):** Android may kill/replace the process and BAL prohibits a
  reliable automatic launch. The notification is the feasible explicit-user-action result.
- **Notification opt-out:** A user may deny notifications or disable the channel. The pending marker
  is retained (never acknowledged while the channel is `IMPORTANCE_NONE`) and reconciliation retries
  on a later process start after re-enable; a manually opened app remains usable. The feature must
  not promise delivery while notifications are disabled.
- **Receiver execution:** `goAsync()` is bounded to a small DataStore/notification operation; no
  network or APK I/O belongs there. A crash between post and delivered acknowledgement can repost
  only the same fixed notification, which is safe.
- **Data preservation:** Preferences keys are additive and survive an APK replacement. No Room data
  changes occur. Rolling back the code leaves unknown Preferences keys harmless; rollback to an
  older signed APK is still rejected by the existing version check rather than risking downgrade.
- **Unresolved manual validation:** On a signed same-key physical device, install an incremented APK
  through the existing updater, confirm the system confirmation, wait for replacement, then tap the
  notification. This is a manual platform check, not an automated acceptance substitute.

## Gate P self-check

Pass. AC-001…AC-006 each map to T-001…T-004 and focused/final verification; all contracts,
dependencies, lifecycle/cancellation ownership, state restoration, and security decisions are frozen.
One writer owns every mutable file, so no ownership overlaps. Only system-integration, DI/build and
final gates are included; Room, worker and Compose-only gates are explicitly excluded as irrelevant.
