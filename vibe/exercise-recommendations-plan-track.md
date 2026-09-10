# Локальная замена недоступного упражнения — tracker

Feature: `exercise-recommendations` · stage 16 / REC-01 / #11

| Task | Status | Owner | AC | Automated check | Observable completion |
|---|---|---|---|---|---|
| T-001 | pending | Android writer | AC-002, AC-003 | `./gradlew :app:testDebugUnitTest --tests "*ExerciseReplacementRankerTest"` | Invalid candidates are filtered and deterministic top-5 fixtures pass. |
| T-002 | pending | Android writer | AC-001, AC-002, AC-005 | `./gradlew :app:compileDebugKotlin` | Hilt resolves the local read boundary and it emits complete immutable snapshots. |
| T-003 | pending | Android writer | AC-001–AC-006 | `./gradlew :app:testDebugUnitTest --tests "*RoutineEditorViewModelTest"` | Reactive replacement states, revalidation, reset, cancellation, and save preservation pass. |
| T-004 | pending | Android writer | AC-002, AC-004, AC-006 | `./gradlew :app:testDebugUnitTest --tests "*RoutineEditorScreenTest"` | Modal states, semantics, Back/Cancel and 2.0 font scale pass. |
| T-005 | pending | Android writer | AC-001–AC-006 | `./gradlew :app:testDebugUnitTest` then `./gradlew :app:assembleDebug` | Version increments once and final project gates pass. |

## AC-to-task-to-test traceability

| AC | Tasks | Evidence/tests |
|---|---|---|
| AC-001 | T-002, T-003, T-005 | local repository fake/state test; `RoutineEditorViewModelTest`; final unit suite/build |
| AC-002 | T-001, T-002, T-003, T-004 | ranker filters; ViewModel explanation states; screen empty/content semantics |
| AC-003 | T-001, T-003 | weighted-Jaccard, shared-primary and `syncId` fixtures; ViewModel integration fixture |
| AC-004 | T-003, T-004 | ViewModel row-copy/reset/cancel tests; Compose confirm/Cancel/Back test |
| AC-005 | T-002, T-003 | repository snapshot changes; ViewModel stale gym/catalog/row/read-only tests; existing transactional save-conflict regression |
| AC-006 | T-003, T-004 | injected compute dispatcher/cancellation test; semantics and `fontScale = 2.0` Compose test |

## Deviations

None. Any change to the frozen filtering, score, durable data, or system integration contract requires an entry here and plan review.

## Findings

- Existing `RoutineEditorViewModel` already keeps edits in memory and delegates persisted availability enforcement to `GymRepository.saveRoutineConfiguration`.
- Existing `GymRepository.observeAvailableExercises` is the availability SSOT; it must not be reimplemented in UI/ranker code.
- `ExerciseCatalogRepository` is intentionally left untouched to avoid changing library catalogue semantics; the feature gets a focused read boundary.

## Command results

| Command | Result |
|---|---|
| Not run during planning | Per AGENTS.md, plan-only edits do not run Gradle. |

## Residual risks

- Similarity derives only from maintained role maps and should be presented as a local suggestion, not training or medical advice.
- An open recommendation can become stale; the specified guard safely asks the user to refresh and does not alter the draft.
