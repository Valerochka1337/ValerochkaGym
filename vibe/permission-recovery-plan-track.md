# Permission recovery — implementation tracker

## Task status

| Task | Status | Owner | Dependencies | AC | Check |
|---|---|---|---|---|---|
| T-001 | pending | implementation writer | — | AC-001, AC-002, AC-004, AC-005 | filtered policy/platform/history tests — not run |
| T-002 | pending | implementation writer | T-001 | AC-001–AC-005 | `*WorkoutsViewModelTest`, `*WorkoutsScreenTest` — not run |
| T-003 | pending | implementation writer | T-001 | AC-001–AC-005 | `*ActiveWorkoutViewModelTest`, `*ActiveWorkoutScreenTest` — not run |
| T-004 | pending | implementation writer | T-001–T-003 | AC-001–AC-005 | target-version check; `:app:compileDebugKotlin` — not run |
| T-005 | pending | tester + readonly Sol/high reviewer | T-004 | AC-001–AC-005 | targeted test + strict review — not run |
| T-006 | pending | root session | T-005 | AC-001–AC-005 | full unit tests then debug assembly — not run |

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

No commands run: plan-only changes. Strict Gate P P1 corrections recorded: lifecycle round-trip
phase, exact notification workout identity, and DI/platform-failure seam. The final lifecycle fix
uses matching `StartActivityForResult` callback → Returned; armed configuration recreation cannot
consume. Affected recheck pending.

## Residual risks

- Platform rationale behavior can vary; current grants plus per-permission history remain deterministic.
- Restored pending work is result-phase- and identity-validated and cannot execute a stale workout action.
