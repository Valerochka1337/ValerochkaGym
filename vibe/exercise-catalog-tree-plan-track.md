# Tracker: exercise-catalog-tree

Plan: [exercise-catalog-tree-plan.md](exercise-catalog-tree-plan.md). Status: `pending | in_progress | done | blocked`.

## Tasks

| Task | Status | Owner | Dependencies | Done evidence |
|---|---|---|---|---|
| T-001 shared history row + projection + tests | done | implementation writer | — | `*ExerciseCatalogProjectionTest` passed |
| T-002 DAO history projection + repository + Hilt | done | implementation writer | T-001 | DAO + reactive repository fake tests + compile |
| T-003 saved ViewModel | done | implementation writer | T-001,T-002 | `*ExerciseLibraryViewModelTest` passed |
| T-004 accessible screen | done | implementation writer | T-003 | Production diff review passed; owner waived screen automation; manual check remains |
| T-005 version/final gates | done | root | T-001…T-004 | Version 13 / 1.3.5; post-rebase full unit suite and debug assembly passed |

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

## Deviations

- The new abstract `WorkoutDao.observeFinishedExerciseHistory()` required seven existing private
  handwritten `WorkoutDao` fakes to implement a `flowOf(emptyList())` no-history projection so
  the existing unit-test source set remains compilable. Their observable test behavior is unchanged.
- Owner explicitly waived screen automation. The unused test-only content seam and its screen test
  were removed rather than claiming coverage for a surface the production screen does not call.
- Reviewer suggestion to let `<25` stabilization rows enter muscle search/facets/leaves is rejected:
  the approved product brief explicitly limits these surfaces to stored contributions `>=25`.

## Findings

- Existing library is flat name/group filtering, but already sources selected gyms through `GymRepository.observeAvailableExercises`.
- AC-007 cannot use set `completedAt`. T-002 owns a narrow finished-only `WorkoutDao` `(exerciseId, workoutId, finishedAt)` projection and its DAO test; it changes neither schema nor migration.
- Top group is stored `ExerciseEntity.muscleGroup`; concrete leaves are only same-group maps. CARDIO/FULL_BODY use all-group results, and unmapped/mismatched custom entries remain reachable without cross-group leakage.
- `ExerciseCatalogRepositoryImplTest` uses private `MutableStateFlow` fakes to prove selected IDs
  reach the availability source, selected labels are retained, and catalog/map/history re-emit
  locally without network access.
- T-001 owns the shared history row before the projector consumes it. T-004 owns the shell-derived project `GymWindowWidthClass`, `MainScaffold`, graph and screen, so no task relies on an unowned file or new adaptive dependency.
- Existing `SELECTED_EXERCISE_ID` pop and atomic picker create/update are preserved regression boundaries.
- Screen automation is intentionally waived by the owner. Manual TalkBack, 2x-font, target-size,
  Back hierarchy and picker-cancel verification remains before release acceptance.
- Independent Gate T and Gate V checks passed with no P0/P1/P2 findings.
- Rebased onto `ValerochkaGym/main@f9eb26e` after the execution-variants feature. Conflicts in
  app version, Hilt bindings and `ExerciseDetailViewModelTest` preserve both feature contracts;
  post-rebase Gate T and Gate V passed with no P0/P1/P2 findings.

## Command results

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
- Owner must perform real-device TalkBack, 2x-font, 48dp target, Back-hierarchy and picker-cancel
  walkthrough before release acceptance; screen automation is explicitly waived for now.
