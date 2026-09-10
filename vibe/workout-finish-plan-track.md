# Workout finish — implementation tracker

## Task status

| Task | Status | Owner | Dependencies | AC | Automated check / evidence |
|---|---|---|---|---|---|
| T-001 | done | implementation writer | Stage 03 integrated | AC-003, AC-004 | `ActiveWorkoutViewModelTest`: 29 tests passed; atomic terminal guard covers suspended repeat, delayed Room, retry, scheduler failure, cancellation and no-active paths. |
| T-002 | done | implementation writer | T-001 | AC-001–AC-004 | `ActiveWorkoutScreenTest`: 11 tests passed; direct 48dp header semantics, exact Room-derived bottom predicate, shared confirmation actions, font scale 2.0 and disabled initiators/dismissal covered. |
| T-003 | done | implementation writer | T-001–T-002 | AC-001–AC-004 | version `32` / `1.3.24`; targeted Kotlin compilation and `spotlessCheck` passed. |
| T-004 | done | independent tester + readonly Sol/high reviewer | T-003 | AC-001–AC-004 | targeted tester gate + strict read-only review; affected recheck after one writer fix — not run |
| T-005 | done | root session | T-004 | AC-001–AC-004 | `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug` — not run |

## AC → task → test traceability

| AC | Task(s) | Required evidence |
|---|---|---|
| AC-001 | T-002 | Compose test finds direct header finish semantics/48dp target without opening overflow. |
| AC-002 | T-002 | Compose state tests prove bottom control is absent for empty/incomplete and reacts to completion changes. |
| AC-003 | T-001, T-002 | Compose test shows both entry points use the same confirmation and Cancel has no callback; ViewModel test confirms no finish path before confirmation. |
| AC-004 | T-001, T-002 | Suspended fake tests prove one finish/upload/navigation; repository failure retry and scheduler-failure-after-success messaging/navigation are exact. |

## Deviations

None. The existing `ConfirmDialog` remains the production wrapper; its finish action slot is shared
with the direct Compose seam instead of exercising Robolectric popup idleness.

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
- Consolidated T-004 P1/P2 fixed: the bottom predicate now reads the latest `workout.exercises`,
  not the reorderable local list; finish confirmation ignores outside/back/cancel dismissal while
  `isFinishing` is true. The narrow screen regression covers a newly arrived incomplete Room
  exercise before the local order catches up and disabled Cancel/confirm actions.

## Command results

- `JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home ./gradlew --no-daemon :app:testDebugUnitTest --tests "*ActiveWorkoutViewModelTest" --tests "*ActiveWorkoutScreenTest" --console=plain` — passed: 29 ViewModel + 10 Compose tests (39 total).
- `JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home ./gradlew --no-daemon :app:compileDebugKotlin --console=plain` — passed.
- `JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home ./gradlew --no-daemon spotlessCheck --console=plain` — passed.
- `JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home ./gradlew --no-daemon :app:testDebugUnitTest --tests "*ActiveWorkoutScreenTest" --console=plain` — passed: 11 Compose tests after consolidated P1/P2 fix.
- `JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home ./gradlew --no-daemon spotlessCheck --console=plain` — passed after consolidated P1/P2 fix.
- `git diff --check` — passed.

## Residual risks

- A process death after local finish and before navigation leaves no active workout by established
  Room behavior; it must not restart the finish flow or navigate a stale screen.
- If adaptive/font-scale verification cannot be established by Compose semantics/tests, inspect via
  accessibility tree; do not request or analyze screenshots without explicit user permission.

## Independent verification

Gate T PASS on 39 targeted tests, source audit and action-seam fontScale2 checks; no redundant run.
Gate V first review found a latest-Room-vs-localOrder predicate race and busy dismissal mismatch.
Both fixed; 11 affected Screen tests and spotless PASS; narrow strict recheck PASS, no open P0/P1/P2.
Root retains only the previously accepted thin popup-wrapper runtime coverage limitation; action
callbacks/disablement are tested independently. Full root tests/debug follow on the stable diff.

## Root final gates

Full unit suite (temporary forkEvery=16, no exclusions): PASS, 1012 tests, 0 failures/errors, 1 skipped, 1m16s. Debug assembly: PASS. Logs /private/tmp/yarumo-workout-finish-final-tests.log and /private/tmp/yarumo-workout-finish-debug.log. Version32/1.3.24 exceeds main29/1.3.21 and previous feature31/1.3.23 exactly once. All ACs accepted; popup-wrapper evidence limit explicitly retained.
