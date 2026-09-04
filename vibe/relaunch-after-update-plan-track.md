# Relaunch after update — tracker

## Task status

| Task | Status | Owner | Dependencies | Observable done condition |
|---|---|---|---|---|
| T-001 | pass | Implementation writer | — | Durable, monotonic DataStore marker and coordinator post/reconcile one fixed, immutable notification; delivery acknowledgement follows successful post only. |
| T-002 | pass | Implementation writer | T-001 | Separate `exported=false` replacement receiver filters `ACTION_MY_PACKAGE_REPLACED`, finishes async work, and application startup reconciles pending delivery. |
| T-003 | pass | Implementation writer | T-001, T-002 | Robolectric regression suite proves receiver, manifest, persistence, notification intent and denied path. |
| T-004 | in progress | Implementation writer | T-001–T-003 | Version increment is applied; final full-suite/debug/release gates are intentionally reserved for the parent integration pass. |

## AC → task → test traceability

| AC | Tasks | Automated evidence |
|---|---|---|
| AC-001 | T-002, T-003 | `AppUpdateManifestTest`; `PostUpdateRelaunchTest` receiver filtering case |
| AC-002 | T-001, T-002, T-003 | `PostUpdateRelaunchTest` DataStore recreation, duplicate and pending-reconciliation cases |
| AC-003 | T-001, T-003 | `PostUpdateRelaunchTest` notification channel/fixed ID and immutable explicit MainActivity PendingIntent with `NEW_TASK`, `CLEAR_TOP`, `SINGLE_TOP` |
| AC-004 | T-001, T-002, T-003 | `PostUpdateRelaunchTest` permission-disabled/SecurityException plus channel-blocked → re-enable → reconcile preserving pending marker |
| AC-005 | T-002, T-003, T-004 | `AppUpdateManifestTest` existing callback tests; full unit suite; debug/release assembly as applicable |
| AC-006 | T-003, T-004 | focused Robolectric commands; `:app:testDebugUnitTest`; `:app:assembleDebug`; release assembly or signing blocker |

## Commands and results

| Command | When | Result |
|---|---|---|
| `./gradlew :app:compileDebugKotlin` | T-001 | pass — 2026-09-04 (`BUILD SUCCESSFUL`) |
| `./gradlew :app:processDebugMainManifest` | T-002 | pass — 2026-09-04 (`BUILD SUCCESSFUL`) |
| `./gradlew :app:testDebugUnitTest --tests "*PostUpdateRelaunchTest" --tests "*AppUpdateManifestTest"` | T-003 | pass — 2026-09-04 (`BUILD SUCCESSFUL`; existing deprecation warnings only) |
| `./gradlew :app:testDebugUnitTest` | T-004 final | pending — parent integration pass |
| `./gradlew :app:assembleDebug` | T-004 final | pending — parent integration pass |
| `./gradlew :app:assembleRelease` | T-004 conditional | pending — parent integration pass; run if signing inputs are available, otherwise record exact missing inputs |

## Deviations

No product, security, persistence, manifest, or version deviation. Full final Gradle gates remain
with the parent integration pass by explicit task scope. Any later behavior change needs its AC/task
impact and explicit approval.

## Findings

- Current `AppUpdateInstallStatusReceiver` publishes to an in-memory channel and
  `AppUpdateViewModel` ignores `Succeeded`; it cannot survive package replacement/process death.
- `MainActivity` has no launcher filter; an enabled activity-alias owns the selected launcher icon.
  The planned notification therefore uses an explicit self-targeted activity intent, not a generic
  launcher intent or alias.
- Current `AndroidAppUpdateInstaller` deliberately uses a private mutable PendingIntent for
  PackageInstaller's status callback; this is retained and is distinct from the planned immutable
  notification PendingIntent.
- Notification delivery must remain pending when the dedicated channel is `IMPORTANCE_NONE`; a
  later re-enable is reconciled and tested before acknowledgement.
- Existing worktree state before this plan: branch reported `main` with an unrelated modified
  `gradle/libs.versions.toml`; this plan does not touch it. The parent session owns isolation and
  any branch decision.

## Residual risks

- Android background-activity-launch policy means auto-opening after ordinary self-update is not a
  reliable or permitted acceptance target; delivery depends on a user notification tap.
- A disabled/denied notification cannot be forced; persistence plus later reconciliation after
  permission/channel re-enable avoids a crash and preserves a recovery opportunity.
- Physical same-key replacement must be manually checked after implementation because Robolectric
  cannot emulate OEM PackageInstaller/replacement timing.
