# Plan: permission-recovery (#10, stage 06)

## Goal and scope

Recover `POST_NOTIFICATIONS` at workout start and `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` at
heart-rate connection. Centralize the decision/history/platform boundary, recheck settings return
on `ON_RESUME`, and keep workout/heart-rate paths usable after denial. This excludes manifest,
OAuth/Calendar, APK-installation, Room, navigation, service/worker, dependency, and schema changes.

## Acceptance criteria

| ID | Criterion |
|---|---|
| AC-001 | Notification and Bluetooth requests occur only from workout-start and heart-rate-connect context; first request is never permanently denied. |
| AC-002 | Current grants, per-permission requested-before history, and rationale choose request versus cancellable app-settings recovery. |
| AC-003 | Only a proven matching settings round-trip `ON_RESUME` rechecks grants and consumes a still-current pending continuation once, never duplicating service/start navigation/BLE scan. |
| AC-004 | Notification denial still starts workout; Bluetooth denial or partial grant keeps workout usable and exposes the existing permission-required device state. |
| AC-005 | Recreation, cancellation, or missing/different active workout prevents stale work; fully granted permissions bypass the launcher. |

## Current → target flow

Current launchers unconditionally continue workout or scan after a result. Target flow is: contextual
intent → live Android snapshot plus history → pure decision → minimal contextual dialog → launcher
or app settings → proven settings-return `ON_RESUME` live recheck → tokenized one-time current continuation. Room
and existing ViewModels remain SSOT for workout existence/state.

## Frozen contracts

- New pure `PermissionRecoveryPolicy` accepts each permission’s `granted`, `requestedBefore`, and
  `shouldShowRationale`: all granted → `Proceed`; any `requestedBefore && !rationale` denied item →
  `OfferSettings`; otherwise → `Request`. Bluetooth proceeds only when both permissions grant.
- New `@Singleton` `PermissionRequestHistory` stores only a boolean per Android permission string in
  the existing injected preferences DataStore, marks each item before system launcher invocation,
  and stores no grant/URI/account/token. No new Hilt module or storage file is needed.
- `PermissionRecoveryController` is the actual DI host seam: it is injected into the existing
  `WorkoutsViewModel` and `ActiveWorkoutViewModel`, owns calls to injected history/pure policy, and
  accepts host-provided platform snapshots. UI never directly owns durable history. The controller
  has no Activity reference or pending continuation state.
- `AndroidPermissionPlatform` unwraps the current Activity for rationale, builds the exact
  `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` URI `package:${context.packageName}`, and checks
  `resolveActivity` before launch. An absent Activity, unresolvable intent, or launch exception
  clears the arm and executes the same one-time optional continuation/fallback, never loops.
- Each host saves primitive pending kind/token, expected workout ID, and per-token settings phase:
  `None`, `Armed`, `Returned`. Dialog opening/unrelated resume never arms it. A successfully
  resolved settings launch uses `ActivityResultContracts.StartActivityForResult`; only its matching
  callback changes `Armed` to `Returned`. A shared `maybeConsumeReturned(token)` clears before the
  side effect and runs from callback and `ON_RESUME` only when lifecycle is resumed. Thus callback
  while resumed consumes immediately; otherwise the next resume consumes once. Configuration
  pause/stop/recreated initial resume preserves `Armed` and cannot consume. Unresolved/launch-failed
  intent clears pending and takes explicit optional fallback; cancelled settings callback is
  `Returned`, rechecks grant, then takes grant/fallback once.
- Workouts start events carry the repository-returned workout ID, rather than `Unit`. Notification
  pending state captures it and, before service/navigation, verifies the current active Room workout
  has that same ID. BLE likewise captures/validates the active workout ID.
- Notification fallback starts service+navigates once without permission. BLE denied/partial fallback
  invokes existing monitor path once to reveal `PermissionRequired`; no partial result is treated as
  full and no second scan begins. Full grant proceeds once; already-granted bypasses launchers.
- Use existing M3 dialogs/buttons, Material tokens, Kotlin UI strings, 48dp targets, semantic
  haptics/TalkBack labels, and `rememberSaveable` only for dialog/pending state. No forced loop.

## Tasks

| ID | Exact files | Owner | Depends on | Actions and automated verification | Done condition | AC |
|---|---|---|---|---|---|---|
| T-001 | `app/src/main/java/com/valerochka1337/valerochkagym/ui/permissions/PermissionRecoveryPolicy.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/ui/permissions/PermissionRecoveryController.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/ui/permissions/AndroidPermissionPlatform.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/data/settings/PermissionRequestHistory.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/ui/permissions/PermissionRecoveryPolicyTest.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/ui/permissions/AndroidPermissionPlatformTest.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/data/settings/PermissionRequestHistoryTest.kt` | implementation writer | — | Implement pure policy, injected controller/history, and adapter. Table-test first/repeated/rationale/permanent, full/partial BLE, independent history. Test wrapped/no Activity rationale, exact action+URI, resolvable/unresolvable settings intent, launch failure, and single optional fallback. `./gradlew :app:testDebugUnitTest --tests "*PermissionRecoveryPolicyTest" --tests "*AndroidPermissionPlatformTest" --tests "*PermissionRequestHistoryTest"` | Deterministic decisions/history; adapter cannot loop or launch an invalid settings intent. | AC-001, AC-002, AC-004, AC-005 |
| T-002 | `app/src/main/java/com/valerochka1337/valerochkagym/ui/workouts/WorkoutsViewModel.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/ui/workouts/WorkoutsScreen.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/ui/WorkoutsViewModelTest.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/ui/workouts/WorkoutsScreenTest.kt` | implementation writer | T-001 | Inject controller; change start event to repository-returned workout ID; replace notification-local path with phase/token/workout-ID pending start and `StartActivityForResult` settings launcher. Matching callback marks Returned; shared lifecycle-aware consume runs callback/resume once. Test armed→config pause/stop→recreated initial resume no-consume; matching callback+resume grant/deny once; unresolved/launch-failed/cancel fallback; same/missing/different active ID, granted bypass and one service/navigation. `./gradlew :app:testDebugUnitTest --tests "*WorkoutsViewModelTest" --tests "*WorkoutsScreenTest"` | Contextual recovery verifies exact started workout and cannot duplicate/act stale across configuration or real settings result. | AC-001–AC-005 |
| T-003 | `app/src/main/java/com/valerochka1337/valerochkagym/ui/active/ActiveWorkoutViewModel.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/ui/active/ActiveWorkoutScreen.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/ui/ActiveWorkoutViewModelTest.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/ui/active/ActiveWorkoutScreenTest.kt` | implementation writer | T-001 | Inject controller; replace BLE-local path with paired policy/captured workout ID and `StartActivityForResult` phase/token recovery. Test direct full scan, first/repeated/partial denial, armed configuration lifecycle no-consume, matching callback+resume grant/deny once, unresolved/launch-failed/cancel fallback, and missing/different workout rejection. `./gradlew :app:testDebugUnitTest --tests "*ActiveWorkoutViewModelTest" --tests "*ActiveWorkoutScreenTest"` | No partial/full confusion, duplicate scan, or obsolete-workout action. | AC-001–AC-005 |
| T-004 | `app/build.gradle.kts`, `vibe/permission-recovery-plan-track.md` | implementation writer | T-001–T-003 | Check target/integrated version and add one #10 `versionCode +1`, patch `versionName +1`; record evidence. `./gradlew :app:compileDebugKotlin` | Version strictly exceeds target once; targeted checks recorded. | AC-001–AC-005 |
| T-005 | *(no file edits; reports to root)* | independent tester + readonly Sol/high reviewer | T-004 | Parallel targeted lifecycle/permission-matrix tester and read-only policy/history/URI/pending/partial/UI/version review; writer performs one consolidated fix/recheck. | No P0/P1; evidence reaches root. | AC-001–AC-005 |
| T-006 | `vibe/permission-recovery-plan-track.md` | root session | T-005 | Stable-diff final checks: `./gradlew :app:testDebugUnitTest`, then `./gradlew :app:assembleDebug`. | AC evidence and outcomes recorded; no P0/P1. | AC-001–AC-005 |

## Ownership and waves

| Owner | Exclusive responsibility |
|---|---|
| implementation writer | T-001–T-004 common policy/history/adapter, both UIs/tests, #10 version bump, tracker evidence. |
| independent tester / readonly Sol-high reviewer | T-005 only; no edits. |
| root session | T-006 final gates and tracker completion. |

One writer preserves stages 03–05 in the shared screens. Wave 1 T-001; wave 2 T-002 then T-003/T-004;
wave 3 tester+reviewer parallel with one fix/recheck; wave 4 root final gates.

## Relevant gates, risks, rollback

- Test first/repeated/permanent/partial states, package settings URI/resolve/launch failure,
  armed configuration pause/stop/recreated initial resume versus matching settings-result callback,
  lifecycle-aware single consumption, rotation/cancel, active-workout disappearance/ID mismatch,
  denial paths, 48dp/semantics,
  font scale 2.0 and adaptive widths. Use accessibility tree, not screenshots.
- No migration/chart/worker/service/OAuth/APK/DI/release gate applies. Plan-only work runs no Gradle.
- Rationale false is ambiguous: durable per-permission history resolves it. Settings return can replay
  stale work: clear-before-side-effect token consumption and workout-ID validation prevent it.
- Rollback removes code; orphaned boolean preference keys are harmless and no workout data changes.

## Gate P self-check

Pass: every AC maps to task/test; one writer owns overlapping files; policy/history/adapter/pending
lifecycle and denial semantics are frozen; relevant strict permission/lifecycle, UI, version, review,
and root final gates are present.
