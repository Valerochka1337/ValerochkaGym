# Workout finish — implementation tracker

## Task status

| Task | Status | Owner | Dependencies | AC | Automated check / evidence |
|---|---|---|---|---|---|
| T-001 | pending | implementation writer | Stage 03 integrated | AC-003, AC-004 | `./gradlew :app:testDebugUnitTest --tests "*ActiveWorkoutViewModelTest"` — not run |
| T-002 | pending | implementation writer | T-001 | AC-001–AC-004 | `./gradlew :app:testDebugUnitTest --tests "*ActiveWorkoutScreenTest"` — not run |
| T-003 | pending | implementation writer | T-001–T-002 | AC-001–AC-004 | target-version check; `./gradlew :app:compileDebugKotlin` — not run |
| T-004 | pending | independent tester + readonly Sol/high reviewer | T-003 | AC-001–AC-004 | targeted tester gate + strict read-only review; affected recheck after one writer fix — not run |
| T-005 | pending | root session | T-004 | AC-001–AC-004 | `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug` — not run |

## AC → task → test traceability

| AC | Task(s) | Required evidence |
|---|---|---|
| AC-001 | T-002 | Compose test finds direct header finish semantics/48dp target without opening overflow. |
| AC-002 | T-002 | Compose state tests prove bottom control is absent for empty/incomplete and reacts to completion changes. |
| AC-003 | T-001, T-002 | Compose test shows both entry points use the same confirmation and Cancel has no callback; ViewModel test confirms no finish path before confirmation. |
| AC-004 | T-001, T-002 | Suspended fake tests prove one finish/upload/navigation; repository failure retry and scheduler-failure-after-success messaging/navigation are exact. |

## Deviations

None. Record any departure from frozen post-persistence failure semantics or file scope before implementation.

## Findings

- Existing repository `finish` is transactional and idempotent, but ViewModel finish is not
  single-flight around persistence, upload scheduling, and navigation.
- Existing mandatory finish dialog is saveable and is opened only from header overflow.
- Bottom region currently contains rest and current-set action; stage 04 adds only the conditional
  finish action there.
- Strict Gate P P1 fixed: `finishJob.isActive` cannot be the terminal guard because the coroutine
  may complete before Room emits the finished workout. The frozen guard is an atomic backing
  `isFinishing` set before launch and reset only on pre-commit repository failure; targeted tests
  retain a nonnull active fake after the full first chain and repeat both normal and scheduler-fail
  finish calls.

## Command results

No commands run: these are plan-only files. Gradle starts after application changes exist. Strict
Gate P affected recheck passed after this documented P1 correction.

## Residual risks

- A process death after local finish and before navigation leaves no active workout by established
  Room behavior; it must not restart the finish flow or navigate a stale screen.
- If adaptive/font-scale verification cannot be established by Compose semantics/tests, inspect via
  accessibility tree; do not request or analyze screenshots without explicit user permission.
