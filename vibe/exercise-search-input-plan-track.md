# Трекер: быстрый ввод поиска упражнений

Статус: pass. Slug: `exercise-search-input`.

## Tasks

| Task | Status | Owner | Dependencies | Done evidence |
|---|---|---|---|---|
| T-001 | pass | Single implementation writer | — | Read-only immediate query flow; production repository projection remains on `@ComputeDispatcher`; targeted VM test passes. |
| T-002 | pass | Single implementation writer | T-001 | Screen field collects immediate query; non-null-repository controlled-dispatcher regressions pass; targeted compile passes. |

## AC-to-task-to-test traceability

| AC | Tasks | Automated evidence | Status |
|---|---|---|---|
| AC-001 | T-001,T-002 | `*ExerciseLibraryViewModelTest`: rapid input visible before compute drains; screen binds the sole field value to lifecycle-aware `query` | pass |
| AC-002 | T-001,T-002 | `*ExerciseLibraryViewModelTest`: controlled compute dispatcher eventually projects latest query only | pass |
| AC-003 | T-001,T-002 | `*ExerciseLibraryViewModelTest`: clear/reset and SavedState + filter/sort production-path regressions | pass |
| AC-004 | T-001,T-002 | `*ExerciseLibraryViewModelTest`: controlled injected compute dispatcher; code review; `:app:compileDebugKotlin` | pass |

## Commands and results

| Command | Result | Notes |
|---|---|---|
| `./gradlew :app:testDebugUnitTest --tests '*ExerciseLibraryViewModelTest'` | pass | `BUILD SUCCESSFUL in 6s` (37 actionable tasks: 10 executed, 27 up-to-date). The first sandboxed invocation could not create the shared Gradle lock; the approved rerun initially caught a missing test import, and the final rerun passed. |
| `./gradlew :app:compileDebugKotlin` | pass | `BUILD SUCCESSFUL in 511ms` (8 actionable tasks, all up-to-date). |
| `./gradlew :app:testDebugUnitTest` | pass | Final stable diff after version bump: `BUILD SUCCESSFUL in 23s` (37 actionable tasks: 19 executed, 18 up-to-date). |
| `./gradlew :app:assembleDebug` | pass | Final stable diff after version bump: `BUILD SUCCESSFUL in 1s` (42 actionable tasks: 4 executed, 38 up-to-date). |
| `git diff --check` | pass | No whitespace errors. |

## Deviations

- Kept the existing conditional `flowOn(@ComputeDispatcher)` for the nullable DAO fallback. The non-null production `ExerciseCatalogRepository` path used by the screen remains compute-dispatched; preserving fallback scheduling avoids making unrelated direct-construction tests race on `Dispatchers.Default`.
- Before commit/push, refreshed `ValerochkaGym/main` had advanced to merge commit `81fcea6` with version `13 / 1.3.5`; this standalone fix therefore increments the app to `14 / 1.3.6`.

## Findings

- Resolved: the screen collects read-only `query` separately from projected `uiState`, so a pending catalog result cannot overwrite editable text.
- Resolved: private `FakeExerciseCatalogRepository` plus a controlled dispatcher covers the non-null production projection path and verifies the final rapid query wins after drain.
- Independent Gate T and Gate V: pass; no open P0/P1/P2 findings.

## Residual risks

- A VM-only test cannot literally assert device focus; preserving text-field composition identity and binding it to immediate state mitigates this without fragile UI infrastructure.
- Manual accessibility/adaptive regression remains a review check because the UI structure and controls are unchanged.
