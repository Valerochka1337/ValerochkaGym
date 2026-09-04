# Muscle selector carousel polish — tracker

## Task status

| Task | Status | Owner | AC | Evidence / done condition |
|---|---|---|---|---|
| T-028 | pass | One implementation writer | AC-044, AC-045, AC-046, AC-047 | Pure clamped/continuous focus-profile tests now cover visible-index resolution and Neutral fallback; retained selector Compose regressions, including 2.0-font-scale equal slots and 48dp neighbours, pass. |
| T-029 | pass | One implementation writer | AC-048 | Version is `18 / 1.3.10`; scoped diff contains only T-028/T-029 application files and `git diff --check` passes. |
| T-030 | pass | Integration coordinator | AC-048 | Full unit suite and debug assembly each exit 0 after the stable implementation diff. |

## AC → task → test traceability

| AC | Task | Automated test / command | Status |
|---|---|---|---|
| AC-044 | T-028 | `MuscleSelectorTest`: clamped monotonic focus profile and visible-index resolver; `MuscleSelectorComposeTest`: retained selector layout semantics; targeted selector command | pass |
| AC-045 | T-028 | `MuscleSelectorTest`: distance-derived interpolation; `MuscleSelectorComposeTest`: drag/fling remains one-settle callback/haptic; targeted selector command | pass |
| AC-046 | T-028 | Existing cyclic mapping, multi-item fling, neighbour tap, external-sync, drag-cancellation and restoration tests; targeted selector command | pass |
| AC-047 | T-028 | Existing 2.0-font-scale, equal-slot/divider, 48dp and TalkBack semantics tests; targeted selector command | pass |
| AC-048 | T-029, T-030 | Scoped `git diff` inspection, `git diff --check`, `./gradlew :app:testDebugUnitTest`, `./gradlew :app:assembleDebug` | pass |

## Deviations

None. Any API-compatibility adjustment to Foundation must preserve the frozen contracts and be recorded here before implementation proceeds.

## Findings

- Baseline already provides the cyclic three-slot Foundation `LazyRow`, snap fling, silent external reconciliation, logical-only restoration, custom TalkBack actions, equal dividers, and 48dp neighbour targets.
- No Room, persistence, domain, DI, navigation, permission, worker, background, or chart surface is in scope.
- Screenshots and render-artifact inspection are not authorized and are not required for this control-level change.

## Command results

| Command | Result | Notes |
|---|---|---|
| `./gradlew :app:testDebugUnitTest --tests '*MuscleSelectorTest' --tests '*MuscleSelectorComposeTest'` | pass | `BUILD SUCCESSFUL` in 9s; 37 actionable tasks (7 executed, 30 up-to-date). |
| `./gradlew :app:testDebugUnitTest --tests '*MuscleSelectorTest' --tests '*MuscleSelectorComposeTest'` | pass | Hardened resolver/endpoint/font-scale pass: `BUILD SUCCESSFUL` in 9s; 37 actionable tasks (8 executed, 29 up-to-date). |
| `git diff --check` | pass | No whitespace errors. |
| `./gradlew :app:testDebugUnitTest` | pass | Final Gate T-030: `BUILD SUCCESSFUL` in 17s; 2026-09-04. |
| `./gradlew :app:assembleDebug` | pass | Final Gate T-030: `BUILD SUCCESSFUL` in 9s; 2026-09-04. |

## Residual risks

- Final visual tuning under real dynamic palettes remains an owner on-device judgement; automated tests cover hierarchy and behavior, not subjective aesthetics.
- If first-layout geometry is unavailable, implementation must render a neutral, side-effect-free profile until it is available.
