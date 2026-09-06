# Трекер: дополнительный подход только для текущего упражнения

## Task status

| Task | Status | Owner | Dependencies | Done evidence |
|---|---|---|---|---|
| T-001 | passed | One implementation writer | Frozen contracts | `*ActiveWorkoutScreenTest` passes with one focused button, no button without focus, and pending-reorder hidden/acknowledged-current coverage. |
| T-002 | passed | One implementation writer | Frozen contracts; T-001 first | `*ActiveWorkoutViewModelTest` passes with stale/nonfocus no-ops. |
| T-003 | passed | One implementation writer | T-001, T-002 | Version is 22 / 1.3.14; full unit suite and debug assembly passed. |

## AC → task → test traceability

| AC | Task | Verification |
|---|---|---|
| AC-001 | T-001 | `ActiveWorkoutScreenTest`: focus and button follow the first unfinished exercise in supplied/local order. |
| AC-002 | T-001 | `ActiveWorkoutScreenTest`: exactly one `Подход`; zero when focus is absent. |
| AC-003 | T-002 | `ActiveWorkoutViewModelTest`: only focus exercise forwards; other/no workout/no focus are no-ops. |
| AC-004 | T-002 | Existing `ActiveWorkoutRepositoryTest`: `addSet copies the last set with the next index`. |
| AC-005 | T-001 | `ActiveWorkoutScreenTest`: accessibility local reorder hides `Подход` until Room acknowledges the new order, then exposes it only for the new current exercise. |
| AC-006 | T-003 | Inspect `app/build.gradle.kts`; `:app:assembleDebug`. |

## Deviations

None at planning time. Any change to `currentFocus()`, repository transaction semantics, persistence, navigation, DI, service, permissions, or background work requires a plan update before implementation.

- This implementation pass runs only the assigned targeted Gradle tests; full `:app:testDebugUnitTest` and `:app:assembleDebug` remain for the final-gate owner.
- Product decision after review: `Подход` is intentionally hidden while the local order differs from Room. The plan's AC-005 now defines this as the required pending-reorder behavior; Room acknowledgement restores the current-exercise action, which uses the unchanged stale/nonfocus guard.

## Findings

- `ActiveWorkoutContent` already derives `currentFocus()` from `workout.copy(exercises = exercises)`, where `exercises` follows local drag order.
- `ExerciseSection` currently always composes `TextButton("Подход")`.
- `ActiveWorkoutViewModel.addSet` currently forwards every id; `ActiveWorkoutRepositoryImpl.addSet` already preserves the required transactional copy/index semantics.
- Default app version is 21 / 1.3.13.

## Command results

| Command | Result |
|---|---|
| Planning-only inspection | Passed; no Gradle commands run because this change set currently contains only plan documents. |
| `ANDROID_HOME=/Users/valerochka1337/Library/Android/sdk ./gradlew :app:testDebugUnitTest --rerun-tasks --tests "*ActiveWorkoutScreenTest" --tests "*ActiveWorkoutViewModelTest" --tests "*ActiveWorkoutRepositoryTest"` | Passed: 2 `ActiveWorkoutScreenTest`, 18 `ActiveWorkoutViewModelTest`, and 24 `ActiveWorkoutRepositoryTest` cases; zero failures/errors; `BUILD SUCCESSFUL in 28s`. |
| `ANDROID_HOME=/Users/valerochka1337/Library/Android/sdk ./gradlew :app:testDebugUnitTest` | Passed: `BUILD SUCCESSFUL in 18s`. |
| `ANDROID_HOME=/Users/valerochka1337/Library/Android/sdk ./gradlew :app:assembleDebug` | Passed: `BUILD SUCCESSFUL in 10s`. |

## Residual risks

- During a pending reorder, `Подход` is absent, so recreation or reorder failure cannot lose or retain an add-set action. After Room emits the order, the ViewModel guard independently rejects a stale callback.
