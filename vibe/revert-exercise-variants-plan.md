# Revert exercise variants — implementation plan

Status: approved Feature Brief / strict path  
Slug: `revert-exercise-variants`  
Baseline: `fix/revert-exercise-variants`, `main` at `8c7d51b`; surgically remove the variant feature merged by `f9eb26e` while retaining PR #25–#27.

## Goal, scope, non-goals, assumptions

Remove all exercise-execution-variant behavior and return the app to one performance/history group per base exercise. Migrate installed v9 databases safely to v10, retaining every non-variant row and every completed/incomplete workout set. Keep the later exercise catalog tree and search-input fixes intact.

In scope: v9→v10 Room migration/schema/tests; variant entities, repositories, DI, Sheets/import/export, domain grouping/parsers, navigation, ViewModel/state/event contracts, sheets and all affected Compose/history/analysis rendering; version `15` / `1.3.7`; targeted and final validation.

Non-goals: deleting historical v8→v9 support, deleting `sectionId`, changing catalog tree/search UX, adding compatibility sync of deprecated variant Sheets, modifying workers/permissions/background policy, or changing unrelated schema/data. Existing remote variant columns/sheets are ignored by the restored readers; no remote deletion is attempted.

Assumptions: this branch is the authorized feature branch; no production implementation is started until its current diff is reconciled with this plan; v9 is the shipping source schema and v8→v9 remains registered for devices that skip directly to v10. One implementation writer owns all production/test changes, so there is no concurrent file overlap.

## Acceptance criteria

- **AC-001:** no variant UI, domain, navigation, repository/DI, Sheets/import/export or performance-group behavior remains; base exercise is the only grouping key.
- **AC-002:** v9→v10 is non-destructive and preserves all non-variant data, including IDs/order, and completed and incomplete `workout_sets` with their completion state/timestamp.
- **AC-003:** the catalog-tree and search-input behavior introduced in PR #25–#27 remains: hierarchy/projector/repository contracts and focused searchable library UI are unchanged except for removal of variant coupling.
- **AC-004:** Room is v10 with handwritten `MIGRATION_9_10`, committed `10.json`, retained `MIGRATION_8_9`, and migration tests for both incremental 9→10 and supported 1→10 path.
- **AC-005:** `versionCode = 15`, `versionName = "1.3.7"`; targeted checks, `AnalysisRenderTest` (without opening snapshots unless explicitly requested), full unit tests and debug assembly pass.

## Current → target flow

Current: Room v9 owns `exercise_variants`, `routine_exercises.variantSyncId`, and workout variant snapshot fields; Room flows feed repositories/use cases and immutable ViewModel state; Compose sheets select/edit variants; analytics/statistics/history use `(exerciseId, variantSyncId)`; navigation serializes the execution group; configuration Sheets writes/reads variants and routine/workout rows include variant IDs.

Target: Room v10 owns base exercises, routine rows, workout sections and sets only. `WorkoutExerciseEntity.sectionId` remains its stable section identity and its unique index remains. DAO/repository flows are the sole SSOT → immutable `StateFlow` UI state (`stateIn(WhileSubscribed(5000))`) → Compose; user events carry only base exercise/section identifiers. Statistics, previous sets, summaries, history, analysis and navigation group/filter by `exerciseId`. Existing remote v9 `Routines` A:M and `Workouts` A:S remain accepted: base fields and section identity are retained, variant ID/name are discarded, `ExerciseVariants` is ignored, existing remote columns are never deleted/rewritten/shifted, and uploads may append new base rows. No new dispatcher, scope, worker, permission, or cancellation owner is needed: retain established injected compute flow boundaries and ViewModel cancellation.

## Frozen architectural decisions and contracts

1. **Migration order and atomicity.** `MIGRATION_9_10` executes in one Room transaction: rebuild `routine_exercises` without `variantSyncId` after its foreign-key-safe ordering; back up `workout_sets`, rebuild `workout_exercises` without variant fields but with `sectionId` and `index_workout_exercises_sectionId`, restore sets, then drop `exercise_variants`. Foreign keys are preserved/enabled and no destructive fallback is added.
2. **Registration and preservation map.** Copy routine `id/routineId/exerciseId/position/restSeconds/plannedSetsJson`; copy workout section `id/workoutId/exerciseId/position/sectionId`; restore every set verbatim (`id`, parent, index, metric values, `isCompleted`, `completedAt`). Variant IDs/names/archival metadata are intentionally discarded. In `DataModule`, append `MIGRATION_9_10` immediately after the unchanged `MIGRATION_8_9`; full 1→10 migration opens through that exact production migration list, not a test-only shortcut.
3. **API/SSOT boundary.** Delete `ExerciseVariant*`, `ExerciseExecutionKey`, `ExecutionGroupToken`, selection/editor sheets and their Hilt bindings; do not replace them with nullable/sentinel variant parameters. `WorkoutDao`, repositories and use cases expose base-exercise rows only; Room remains SSOT and multi-write operations remain DAO transactions.
4. **Navigation/restoration.** Restore the base route `exercise_detail/{exerciseId}` and remove variant arguments from all callbacks and SavedStateHandle reads. Existing nav restore/back behavior remains; no variant UI state is saveable or recoverable.
5. **UX/accessibility/adaptive.** Remove variant labels/actions/selection sheets rather than hide them. Existing loading/empty/error/content states, 48dp actions, semantics, adaptive layout, colors, `GymMotion`, and `GymHaptics` remain unchanged. No new UI interaction is introduced.
6. **Sync/background.** Do not schedule work or request permissions. The parsers/importers accept already-written v9 `Routines` A:M and `Workouts` A:S without moving columns: retain each base field and `sectionId`, discard only variant ID/name, ignore the `ExerciseVariants` sheet, and append new base rows using the compatible shape. Never delete, rewrite or shift existing remote columns. Existing unique work/cancellation and retry policy remain unchanged.

## Tasks

### T-001 — freeze the surgical reverse boundary

- **Owner:** implementation writer
- **Dependencies:** none
- **Files:** all files touched by `f9eb26e`; frozen overlap symbols include `WorkoutDao.observeFinishedExerciseHistory`, `ExerciseWorkoutHistoryRow`, `DomainModule.bindExerciseCatalogRepository`, `WorkoutDaoTest` fixtures/fakes, and later edits in `ExerciseCatalogRepositoryImpl.kt`, `ExerciseCatalogProjector.kt`, `ExerciseCatalogRepository.kt`, `ExerciseLibraryScreen.kt`, `ExerciseLibraryViewModel.kt`, `GymNavGraph.kt`, `GymWindowWidthClass.kt`, `MainScaffold.kt` and their PR #25–#27 tests.
- **Actions:** compare `f9eb26e^..f9eb26e` and `f9eb26e..HEAD`; make a per-symbol reverse list. Apply deletions/restorations by symbol, never wholesale-revert post-merge files. Confirm the frozen DAO/history, catalog DI/contract, catalog-tree and search symbols/tests remain before and after edits.
- **Automated verification:** `git diff --check`; `./gradlew :app:testDebugUnitTest --tests "*WorkoutDaoTest" --tests "*ExerciseCatalogRepositoryImplTest" --tests "*ExerciseCatalogProjectionTest" --tests "*ExerciseLibrary*"`.
- **Done:** the implementation diff contains no accidental rollback of PR #25–#27; `observeFinishedExerciseHistory`/its relation and `bindExerciseCatalogRepository` retain post-merge contracts; DAO/catalog DI-contract/library targeted tests pass.
- **AC:** AC-003.

### T-002 — migrate Room v9 safely to v10

- **Owner:** implementation writer (exclusive Room/schema owner)
- **Dependencies:** T-001
- **Files:** `data/db/GymDatabase.kt`, `di/DataModule.kt`; delete `data/db/dao/ExerciseVariantDao.kt`, `data/db/entity/ExerciseVariantEntity.kt`; update `data/db/entity/RoutineExerciseEntity.kt`, `WorkoutExerciseEntity.kt`, `data/db/dao/WorkoutDao.kt`, affected relation rows; add `app/schemas/com.valerochka1337.valerochkagym.data.db.GymDatabase/10.json`; update/add `Migration8To9Test.kt`, `Migration9To10Test.kt`, `Migration1To10Test.kt` (and retire superseded `Migration1To9Test.kt` only if no longer meaningful).
- **Actions:** set database v10; keep `MIGRATION_8_9` unchanged and append `MIGRATION_9_10` immediately after it in `DataModule`'s production registration list. Implement handwritten `MIGRATION_9_10` under the frozen transaction/preservation contract; remove variant DAO/entity and all variant columns from Room models; export/commit v10 schema. Seed a full genuine v9 fixture with real `exercise_variants` references in routine rows and workout snapshots plus completed/incomplete sets; open it via the production Room-v10 builder. Assert no variant table/columns, empty `PRAGMA foreign_key_check`, all expected routine/workout indexes (including `sectionId` unique index), generated committed `10.json` schema match, and exact copied non-variant rows/sets. Separately migrate a v1 fixture through the exact production migration list, ending with unchanged `MIGRATION_8_9` then appended `MIGRATION_9_10`.
- **Automated verification:** `./gradlew :app:testDebugUnitTest --tests "*Migration8To9Test" --tests "*Migration9To10Test" --tests "*Migration1To10Test"`.
- **Done:** Room validates the committed generated v10 schema; 9→10 and the exact production-list 1→10 paths pass with empty FK check, expected indexes and preserved fixtures; no destructive fallback appears.
- **AC:** AC-002, AC-004.

### T-003 — remove variant persistence/domain/sync contracts

- **Owner:** implementation writer
- **Dependencies:** T-002
- **Files:** `data/ActiveWorkoutRepositoryImpl.kt`, delete `data/ExerciseVariantRepositoryImpl.kt`; `data/google/ConfigurationSheetsRepository.kt`, `SheetsRepository.kt`, `WorkoutImportRepository.kt`; `di/DomainModule.kt`; `domain/ActiveWorkoutRepository.kt`, delete `ExerciseVariantRepository.kt`, `ExerciseVariantSheetRows.kt`, `ExerciseExecutionKey.kt`, `ExecutionGroupToken.kt`; `domain/PreviousSetsUseCase.kt`, `RoutineRowMapper.kt`, `RoutineRowParser.kt`, `RoutineUpdateUseCase.kt`, `WorkoutRowMapper.kt`, `WorkoutRowParser.kt`, `WorkoutStatsUseCase.kt`, `domain/analysis/AnalyticsEngine.kt`, `AnalyticsModels.kt`; corresponding parser/import/upload/worker fixture tests, deleting obsolete variant-only tests.
- **Actions:** restore one-key base-exercise contracts; remove variant upload/import code and Hilt bindings. Parsers/importers must accept v9 `Routines` A:M and `Workouts` A:S, retain all base fields and `sectionId`, discard variant ID/name, and ignore `ExerciseVariants`; uploads append compatible base rows without deleting, rewriting or shifting remote columns. Preserve import transaction semantics, unique worker behavior, injected compute dispatcher/`flowOn`, and base exercise IDs. Update handwritten fakes and parser/mapper/import/upload/worker fixtures for those exact remote shapes.
- **Automated verification:** `./gradlew :app:testDebugUnitTest --tests "*WorkoutRow*" --tests "*RoutineRow*" --tests "*ConfigurationImport*" --tests "*WorkoutImport*" --tests "*SheetsRepository*" --tests "*Upload*"`.
- **Done:** no production `ExerciseVariant`, `variantSyncId`, `variantNameSnapshot`, execution-group token/key, or active variant sheet contract remains; exact A:M/A:S parser, import, upload and worker fixtures prove base fields/section identity retained, variant values discarded and no remote-column mutation; targeted tests pass.
- **AC:** AC-001, AC-003.

### T-004 — restore base-exercise UI, state and navigation

- **Owner:** implementation writer
- **Dependencies:** T-002, T-003
- **Files:** delete `ui/active/ExerciseVariantSelectionSheet.kt`, `ui/exercise/ExerciseVariantEditorSheet.kt`; update `ui/active/ActiveWorkoutScreen.kt`, `ActiveWorkoutViewModel.kt`, `ui/routine/RoutineEditorScreen.kt`, `RoutineEditorViewModel.kt`, `ui/exercise/ExerciseDetailScreen.kt`, `ExerciseDetailViewModel.kt`, `ui/history/WorkoutDetailScreen.kt`, `WorkoutDetailViewModel.kt`, `ui/summary/WorkoutSummaryScreen.kt`, `WorkoutSummaryViewModel.kt`, `ui/analysis/AnalysisScreen.kt`, `AnalysisViewModel.kt`, `ProgressCards.kt`, `ui/navigation/GymNavGraph.kt`; corresponding UI/ViewModel/render tests.
- **Actions:** remove immutable variant fields/events and sheets, use `exerciseId`/section ID only, restore `exercise_detail/{exerciseId}`, and update callbacks. Retain existing loading/empty/error/content treatment, lifecycle-aware collection, `stateIn` ownership, adaptive shell and catalog tree/search UI. Keep analytics rendering semantics but remove variant suffix/group picker; run its render test without viewing generated snapshots.
- **Automated verification:** `./gradlew :app:testDebugUnitTest --tests "*ActiveWorkout*" --tests "*RoutineEditor*" --tests "*ExerciseDetail*" --tests "*WorkoutDetail*" --tests "*WorkoutSummary*" --tests "*AnalysisRenderTest"`.
- **Done:** no variant action, label, accessibility semantics, route argument, SavedStateHandle key or state field is reachable; affected UI/ViewModel tests and `AnalysisRenderTest` pass, with no snapshot opened/analyzed absent explicit user permission.
- **AC:** AC-001, AC-003, AC-005.

### T-005 — version and strict-path verification

- **Owner:** implementation writer (shared choke-point owner)
- **Dependencies:** T-001–T-004
- **Files:** `app/build.gradle.kts`; all changed tests; `vibe/revert-exercise-variants-plan-track.md`.
- **Actions:** set `versionCode` 15 / `versionName` `1.3.7`; before any commit/push compare version and intended diff with `main` and check current branch/status/log. Run targeted tests during tasks, then once on the stable diff run final gates sequentially; record commands/results/deviations. Do not commit, push, merge, create/switch/delete branches/worktrees, or view analysis snapshots.
- **Automated verification:** `./gradlew :app:testDebugUnitTest`; then `./gradlew :app:assembleDebug`.
- **Done:** exact version values and clean Gradle results are recorded; no release-only/dependency/permission/worker gate is applicable.
- **AC:** AC-005.

## File ownership and execution waves

| Owner | Exclusive boundary |
|---|---|
| Implementation writer | All T-001–T-005 production, test, schema and build files; this intentionally serializes the Room/DI/navigation choke points. |
| Planner | Only `vibe/revert-exercise-variants-plan.md` and `vibe/revert-exercise-variants-plan-track.md`; no application writes. |

| Wave | Tasks | Rule |
|---|---|---|
| 1 | T-001 | establish reverse boundary before edits |
| 2 | T-002 | database contract first, exclusively |
| 3 | T-003 | domain/data/sync against frozen v10 contract |
| 4 | T-004 | UI/navigation against frozen base-only APIs |
| 5 | T-005 | stable-diff validation, sequential |

## Quality gates

Relevant conditional gates: handwritten Room migration + generated schema and incremental/full-path tests; navigation/back/state restoration; Compose semantics/adaptive/font-scale regression review; computed analytics render gate; Hilt compile after removed bindings; version/Git comparison. No new dependency, permission, manifest, service, WorkManager, release-sensitive dependency, or external API contract is introduced, so their additional gates do not apply. `AnalysisRenderTest` runs, but its snapshots are not opened or inspected without explicit permission.

Final project gates, once after the stable diff and in this order:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

## Risks, unresolved questions, rollback/data preservation

- **Risk:** naive `git revert f9eb26e` would erase subsequent edits in overlapping nav, DAO, catalog and UI files. Mitigation: T-001 symbol-level reverse and catalog/search tests.
- **Risk:** SQLite table rebuild can lose FK/index/set rows. Mitigation: single migration transaction, explicit backup/restore, full v9 variant-reference fixture opened by Room v10, `PRAGMA foreign_key_check`, expected-index assertions, committed-schema validation and the exact production-list 1→10 test.
- **Risk:** remotely stored v9 variant rows/columns can remain. Mitigation: do not delete, rewrite or shift user cloud data; parsers/importers accept `Routines` A:M and `Workouts` A:S, retain base fields/section identity, discard only variant ID/name and ignore `ExerciseVariants`; uploads append compatible base rows. The app cannot recreate variant associations after v10 by design.
- **Risk:** data loss is irreversible for local variant metadata after v10. Mitigation: users retain all workouts/routines/sets but not the discarded variant labels/IDs; backup before update is recommended. A code rollback must restore v9/v10-compatible migration support rather than downgrade the DB.
- **Unresolved blocker:** none. Confirm only that no independent uncommitted application changes overlap T-001 before implementation; otherwise the implementation writer must preserve them and record a deviation.

## Gate P self-check

Pass: AC-001→T-003/T-004 exact Sheets/base-only and UI tests; AC-002→T-002 full-v9 migration fixture, FK/index/set assertions; AC-003→T-001/T-003/T-004 frozen DAO/catalog-DI/tree/search tests; AC-004→T-002 appended production migration list, generated schema and full-path tests; AC-005→T-004/T-005 render, full-unit and assemble checks. Every task has an observable done condition and command. Ownership has one implementation writer (no overlap); Room/schema/DataModule registration, Hilt and navigation contracts are frozen before dependent work; only relevant conditional gates are included.
