# Revert exercise variants — tracker

Status legend: `pending` | `in_progress` | `done` | `blocked`

| Task | Status | Owner | Depends on | AC | Automated evidence | Done evidence |
|---|---|---|---|---|---|---|
| T-001 surgical reverse boundary | done | implementation writer | — | AC-003 | `./gradlew :app:testDebugUnitTest --tests "*WorkoutDaoTest" --tests "*ExerciseCatalogRepositoryImplTest" --tests "*ExerciseCatalogProjectionTest" --tests "*ExerciseLibrary*"` | Symbol-level diff retains PR #25–#27 plus frozen DAO/history/catalog DI contracts. |
| T-002 Room v9→v10 | done | implementation writer | T-001 | AC-002, AC-004 | `./gradlew :app:testDebugUnitTest --tests "*Migration8To9Test" --tests "*Migration9To10Test" --tests "*Migration1To10Test"` | Exact production migration list, v10 schema, FK/indexes and all fixture sets pass. |
| T-003 domain/data/sync reverse | done | implementation writer | T-002 | AC-001, AC-003 | `./gradlew :app:testDebugUnitTest --tests "*WorkoutRow*" --tests "*RoutineRow*" --tests "*ConfigurationImport*" --tests "*WorkoutImport*" --tests "*SheetsRepository*" --tests "*Upload*"` | A:M/A:S fixtures retain base fields/section ID, discard variants, ignore sheet and append safely. |
| T-004 UI/state/navigation reverse | done | implementation writer | T-002, T-003 | AC-001, AC-003, AC-005 | `./gradlew :app:testDebugUnitTest --tests "*ActiveWorkout*" --tests "*RoutineEditor*" --tests "*ExerciseDetail*" --tests "*WorkoutDetail*" --tests "*WorkoutSummary*" --tests "*AnalysisRenderTest"` | Base-only UI/routes; render test runs; snapshots not viewed. |
| T-005 version/final verification | done | root | T-001–T-004 | AC-005 | `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug` | Version is 15/1.3.7; both final gates pass on the stable diff. |

## AC → task → test traceability

| AC | Implementing task(s) | Test/verification |
|---|---|---|
| AC-001 | T-003, T-004 | exact v9 A:M/A:S parser/import/upload/worker fixtures; affected ViewModel/UI suite; no-variant source audit. |
| AC-002 | T-002 | full v9 variant-reference fixture checks all rows/sets, `PRAGMA foreign_key_check` and expected indexes after Room-v10 open. |
| AC-003 | T-001, T-003, T-004 | frozen `WorkoutDaoTest`, catalog repository/projection DI-contract/library tests plus symbol-level overlap audit. |
| AC-004 | T-002 | `Migration8To9Test`, `Migration9To10Test`, `Migration1To10Test`; exact production list appends `MIGRATION_9_10` after unchanged `MIGRATION_8_9`; committed `10.json`. |
| AC-005 | T-004, T-005 | `AnalysisRenderTest` (no snapshot viewing), full unit suite, debug assembly, version/Git checks. |

## Deviations

- Final full unit suite and debug assembly ran once in the root session on the stable diff; implementation ran only the required targeted gates. No acceptance criterion or data contract changed.

## Findings

- Baseline is `fix/revert-exercise-variants`; `main` is `8c7d51b` and post-variant catalog/search changes overlap several reverse targets.
- Current database is v9 and app version is 14 / 1.3.6.
- Variant feature entered via merge `f9eb26e`; safe removal requires a forward v9→v10 migration, not a source/database rollback.
- Sheets compatibility is forward-only: existing v9 `Routines` A:M and `Workouts` A:S remain in place; only variant values are discarded locally and `ExerciseVariants` is ignored.

## Command results

| Command | Result | Notes |
|---|---|---|
| Planning-only repository inspection | pass | No Gradle command run: plan documents alone do not authorize/require application gates. |
| `git diff --check` | pass | No whitespace errors after the surgical reverse. |
| `./gradlew :app:testDebugUnitTest --tests "*Migration8To9Test" --tests "*Migration9To10Test" --tests "*Migration1To10Test"` | pass | Retained v8→v9, v9→v10 fixture and exact production-list v1→v10 all pass. |
| `./gradlew :app:testDebugUnitTest --tests "*WorkoutRow*" --tests "*RoutineRow*" --tests "*ConfigurationImport*" --tests "*WorkoutImport*" --tests "*SheetsRepository*" --tests "*Upload*"` | pass | 90 targeted Sheets/parser/import/upload tests pass. |
| `./gradlew :app:testDebugUnitTest --tests "*WorkoutDaoTest" --tests "*ExerciseCatalogRepositoryImplTest" --tests "*ExerciseCatalogProjectionTest" --tests "*ExerciseLibrary*"` | pass | Preserved DAO history and catalog tree/search contracts pass. |
| `./gradlew :app:testDebugUnitTest --tests "*ActiveWorkout*" --tests "*RoutineEditor*" --tests "*ExerciseDetail*" --tests "*WorkoutDetail*" --tests "*WorkoutSummary*" --tests "*AnalysisRenderTest"` | pass | UI/ViewModel/render target passes; generated render snapshots were not opened. |
| `./gradlew :app:testDebugUnitTest --tests "*Migration2To3Test" --tests "*Migration3To4Test"` | pass | Legacy Room schema-validation fixtures also open through v8→v10. |
| `./gradlew :app:testDebugUnitTest --tests "*WorkoutRowParserTest" --tests "*RoutineRowParserTest"` | pass | Explicit v9 A:S/A:M fixtures retain base/section data and discard variant cells. |
| `./gradlew :app:testDebugUnitTest --tests "*GymRoutesTest" --tests "*ExerciseDetailViewModelTest" --tests "*WorkoutImportRepositoryTest" --tests "*WorkoutRowParserTest" --tests "*RoutineRowParserTest" --tests "*Migration*To10Test"` | pass | P1 fix pass: A:M Routines read, strict A:S base-tuple validation/fail-fast import, full v9 Room fixture and legacy detail-route restoration. |
| `./gradlew :app:testDebugUnitTest --tests "*GymRoutesTest"` | pass | P1 fix pass 2: real Robolectric `NavHostController` state saved on exact v9 `exercise_detail/{exerciseId}/{executionGroup}` and restored on the current base-only graph. |
| `./gradlew :app:testDebugUnitTest` | pass | Final full unit suite; `BUILD SUCCESSFUL` in 15s. |
| `./gradlew :app:assembleDebug` | pass | Final debug assembly; `BUILD SUCCESSFUL` in 6s. |

## Residual risks

- v10 deliberately discards local variant metadata while preserving all non-variant records and sets; advise backup before update.
- Existing remote A:M/A:S columns and variant rows are retained remotely; no column is deleted, rewritten or shifted.
- Snapshot visual inspection remains blocked unless the user explicitly asks for it.
- P1 fix passes resolved: existing v9 `Routines` are read through A:M; v9 `Workouts` validates canonical section/base tuples before an import transaction, while R:S variant cells remain ignored; exact legacy `exercise_detail/{exerciseId}/{executionGroup}` navigation state restores into the base-only detail destination.
