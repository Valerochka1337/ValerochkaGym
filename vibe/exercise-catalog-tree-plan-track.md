# Tracker: exercise-catalog-tree

Plan: [exercise-catalog-tree-plan.md](exercise-catalog-tree-plan.md). Status: `pending | in_progress | done | blocked`.

## Tasks

| Task | Status | Owner | Dependencies | Done evidence |
|---|---|---|---|---|
| T-001 shared history row + projection + tests | done | implementation writer | — | `*ExerciseCatalogProjectionTest` passed |
| T-002 DAO history projection + repository + Hilt | done | implementation writer | T-001 | DAO + reactive repository fake tests + compile |
| T-003 saved ViewModel | done | implementation writer | T-001,T-002 | `*ExerciseLibraryViewModelTest` passed, including legacy type restoration |
| T-004 accessible screen | done | implementation writer | T-003 | Production diff review passed; owner waived screen automation; manual check remains |
| T-005 version/final gates | done | root | T-001…T-004 | Version 13 / 1.3.5; final full unit suite and debug assembly passed |
| T-006 flat overview + search-sheet facets | done | implementation writer | T-001,T-003,T-004 | `:app:compileDebugKotlin` and targeted projector/VM tests passed after final sheet correction |
| T-007 flat catalog top reset + fast rearrangement | done | implementation writer | T-006 | targeted projector/VM + compile passed; screen behavior is owner-waived manual/code-review evidence |

## AC → task → test

| AC | Tasks | Evidence |
|---|---|---|
| AC-001 | T-001,T-003,T-004 | projection, VM, screen |
| AC-002 | T-001,T-004 | projection, screen |
| AC-003 | T-001,T-003,T-004 | projection, VM, screen |
| AC-004 | T-001,T-004 | projection, screen |
| AC-005 | T-001,T-003 | projection, VM |
| AC-006 | T-001,T-003 | projection, VM |
| AC-007 | T-001,T-002,T-003 | WorkoutDao `finishedAt`, projection/repository/VM |
| AC-008 | T-001,T-002,T-003 | projection/repository/VM |
| AC-009 | T-002,T-003 | gym DAO/repository, VM |
| AC-010 | T-002,T-003 | VM reactive update |
| AC-011 | T-001,T-004 | projection, screen |
| AC-012 | T-003,T-004 | VM, screen reset |
| AC-013 | T-003,T-004 | active/routine VM, screen |
| AC-014 | T-002,T-003 | repository, VM local flows |
| AC-015 | T-004 | Production review; owner-waived real-device accessibility/adaptive walkthrough remains |
| AC-016 | T-001,T-002,T-005 | projection, gym regression, final gates |
| AC-017 | T-006 | projector/VM, owner visual verification |
| AC-018 | T-006 | projector/VM (type family, contextual counts, no hidden muscle state); owner visual verification of separate sheets |
| AC-019 | T-007 | code review/manual: `LazyListState.scrollToItem(0)` on effective sort/filter and `GymMotion.spatialFast()` placement; screen automation waived |

## Deviations

- The new abstract `WorkoutDao.observeFinishedExerciseHistory()` required seven existing private
  handwritten `WorkoutDao` fakes to implement a `flowOf(emptyList())` no-history projection so
  the existing unit-test source set remains compilable. Their observable test behavior is unchanged.
- Owner explicitly waived screen automation. The unused test-only content seam and its screen test
  were removed rather than claiming coverage for a surface the production screen does not call.
- Reviewer suggestion to let `<25` stabilization rows enter muscle search labels is rejected:
  the approved product brief explicitly limits those labels to stored contributions `>=25`.
- Owner correction replaces persistent inline facet rows with two search-field actions and separate
  Material 3 filter/sort sheets. The individual-muscle facet and its SavedState field are removed.
- Direct owner supersession removes the remaining concrete-muscle hierarchy, its Back/SavedState
  behavior, and quick-section API entirely. The final catalog is flat with type/origin/stored-group
  filters only; route pop and picker selection callbacks remain unchanged.

## Findings

- Existing library is flat name/group filtering, but already sources selected gyms through `GymRepository.observeAvailableExercises`.
- AC-007 cannot use set `completedAt`. T-002 owns a narrow finished-only `WorkoutDao` `(exerciseId, workoutId, finishedAt)` projection and its DAO test; it changes neither schema nor migration.
- Muscle maps supply search labels only at contribution `>=25` within stored `ExerciseEntity.muscleGroup`; `CARDIO`/`FULL_BODY`, unmapped rows and mismatched maps remain normal flat catalog rows and never create cross-group filter membership.
- `ExerciseCatalogRepositoryImplTest` uses private `MutableStateFlow` fakes to prove selected IDs
  reach the availability source, selected labels are retained, and catalog/map/history re-emit
  locally without network access.
- T-001 owns the shared history row before the projector consumes it. T-004 owns the shell-derived project `GymWindowWidthClass`, `MainScaffold`, graph and screen, so no task relies on an unowned file or new adaptive dependency.
- Existing `SELECTED_EXERCISE_ID` pop and atomic picker create/update are preserved regression boundaries.
- Screen automation is intentionally waived by the owner. Manual TalkBack, 2x-font, target-size,
  direct route Back and picker-cancel verification remains before release acceptance.
- Independent Gate T and Gate V checks passed with no P0/P1/P2 findings.
- Rebased onto `ValerochkaGym/main@f9eb26e` after the execution-variants feature. Conflicts in
  app version, Hilt bindings and `ExerciseDetailViewModelTest` preserve both feature contracts;
  post-rebase Gate T and Gate V passed with no P0/P1/P2 findings.

## Command results

Passed after final search-sheet correction: `./gradlew :app:compileDebugKotlin`.

Passed after final search-sheet correction: `./gradlew :app:testDebugUnitTest --tests '*ExerciseCatalogProjectionTest' --tests '*ExerciseLibraryViewModelTest'`.

Passed after final search-sheet correction: `git diff --check`.

Passed after flat-catalog supersession: `./gradlew :app:compileDebugKotlin`.

Passed after flat-catalog supersession: `./gradlew :app:testDebugUnitTest --tests '*ExerciseCatalogProjectionTest' --tests '*ExerciseLibraryViewModelTest'`.

Passed after flat-catalog supersession: `git diff --check`.

Passed on final stable diff: `./gradlew :app:testDebugUnitTest` (`BUILD SUCCESSFUL`).

Passed on final stable diff: `./gradlew :app:assembleDebug` (`BUILD SUCCESSFUL`).

Passed on final stable diff: Gate T and Gate V, no open P0/P1/P2 findings.

Passed after rebase: `./gradlew :app:compileDebugKotlin`.

Passed after rebase: `./gradlew :app:testDebugUnitTest --tests '*ExerciseCatalogProjectionTest'
--tests '*ExerciseCatalogRepositoryImplTest' --tests '*WorkoutDaoTest'
--tests '*ExerciseDetailViewModelTest' --tests '*ExecutionGroupTokenTest'`.

Passed after rebase: `./gradlew :app:testDebugUnitTest` (`BUILD SUCCESSFUL`).

Passed after rebase: `./gradlew :app:assembleDebug` (`BUILD SUCCESSFUL`).

Passed after rebase: final `git diff --check`.

Version check against current remote `main`: `versionCode` 12 → 13,
`versionName` 1.3.4 → 1.3.5.

## Residual risks

- Execution variants are integrated on the feature branch; shared DAO, Hilt, navigation and fake
  contracts passed targeted tests and independent review.
- The narrow DAO query is a maintenance surface, but it is required to preserve approved `finishedAt` recency semantics.
- Owner must perform real-device TalkBack, 2x-font, 48dp target, direct route Back and picker-cancel
  walkthrough before release acceptance; screen automation is explicitly waived for now.
