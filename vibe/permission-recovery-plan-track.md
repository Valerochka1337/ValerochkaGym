# Permission recovery — implementation tracker

## Task status

| Task | Status | Owner | Dependencies | AC | Check |
|---|---|---|---|---|---|
| T-001 | done | implementation writer | — | AC-001, AC-002, AC-004, AC-005 | policy/platform/history filter passed: 6 tests. |
| T-002 | done | implementation writer | T-001 | AC-001–AC-005 | Shared rendered host persists notification work, transfers opaque action ownership to the ViewModel before clear, and consumes a matching settings callback only while resumed. |
| T-003 | done | implementation writer | T-001 | AC-001–AC-005 | The same rendered shared host single-flights paired BLE request launches and gives the ViewModel one tokenized, exact-workout monitor action. |
| T-004 | done | implementation writer | T-001–T-003 | AC-001–AC-005 | version `34` / `1.3.26`; targeted Kotlin compilation and `spotlessCheck` passed. |
| T-005 | pass | tester + readonly Sol/high reviewer | T-004 | AC-001–AC-005 | targeted test + strict review — not run |
| T-006 | pass | root session | T-005 | AC-001–AC-005 | full unit tests then debug assembly — not run |

## AC traceability

| AC | Tasks | Evidence |
|---|---|---|
| AC-001 | T-001–T-003 | first-request policy and contextual host-launcher tests |
| AC-002 | T-001–T-003 | per-permission history/rationale and settings/cancel tests |
| AC-003 | T-002–T-003 | armed config pause/stop/recreated initial resume no-consume; matching `StartActivityForResult` callback plus resume grant/deny once tests |
| AC-004 | T-001–T-003 | notification fallback; BLE denied/partial permission-required tests |
| AC-005 | T-001–T-003 | granted bypass, rotation/cancel/stale token/workout-ID tests |

## Deviations

None.

## Findings

- Current notification result always starts workout; current BLE result always scans.
- Existing screens lack rationale/history/settings-return/current-intent recovery; `startEvents` loses
  the created workout identity by carrying `Unit`.
- Existing preferences DataStore supports dedicated request-history keys without mixing user settings.

## Command results

- `:app:testDebugUnitTest --tests "*PermissionRecoveryPolicyTest" --tests "*AndroidPermissionPlatformTest" --tests "*PermissionRequestHistoryTest"` — passed: 6 tests.
- `:app:testDebugUnitTest --tests "*PermissionRecoveryPolicyTest" --tests "*PermissionSettingsRecoveryTest" --tests "*PermissionRecoveryHostTest" --tests "*AndroidPermissionPlatformTest" --tests "*PermissionRequestHistoryTest" --tests "*WorkoutsViewModelTest" --tests "*WorkoutsScreenTest" --tests "*ActiveWorkoutViewModelTest" --tests "*ActiveWorkoutScreenTest"` — passed: 70 tests.
- `:app:testDebugUnitTest --tests "*PermissionRecoveryHostTest" --tests "*PermissionSettingsRecoveryTest" --tests "*WorkoutsViewModelTest" --tests "*ActiveWorkoutViewModelTest"` — passed: 49 tests after opaque-token and Settings-launch single-flight regressions.
- `spotlessApply :app:compileDebugKotlin spotlessCheck` — passed.

## Residual risks

- Platform rationale behavior can vary; current grants plus per-permission history remain deterministic.
- Restored pending work is result-phase- and identity-validated and cannot execute a stale workout action.

## Root final acceptance

Gate T/V final narrow recheck PASS; all P1/P2 closed. Opaque UUID action tokens, synchronous
Settings launch claim, ViewModel-owned continuations, and actual rendered registry/lifecycle
regressions cover both notification and BLE. Legitimate same-workout re-entry remains usable.
Full testDebugUnitTest PASS1m15s:1035 tests,0 failures/errors,1 existing skipped test, using the
previously documented temporary Mac forkEvery16 init script without exclusions. assembleDebug
PASS10s; spotless and diff check PASS. Logs: /private/tmp/yarumo-permissions-final-tests.log and
/private/tmp/yarumo-permissions-final-debug.log. origin/main freshly verified cc590a4, version29/1.3.21;
feature34/1.3.26. No new Room schema, manifest permission, or foreground-service semantic change.
Rollback may restore the prior UI while retaining harmless request-history DataStore keys; never
clear user workout data. No screenshots or device permission grants were used for validation.
