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

---

## Room v11 recovery extension — target v12

### Goal, scope, non-goals, assumptions

The installed v10 target crashes when opening the emulator's real file-backed database at `user_version = 11`: Room has no registered 11→10 downgrade or 11→newer forward path. Advance the current target to Room v12 and recover every supported installed database without changing app behavior. The supported production paths are **1…9→10→12**, **10→12**, and **11→12**.

In scope: database version `12`, generated `12.json`, one centralized production migration list, an intentionally no-op Room-validation `MIGRATION_10_12`, a transactional handwritten `MIGRATION_11_12`, file-backed migration tests seeded from the exact emulator v11 DDL, and a local non-source-controlled test of a read-only emulator copy. The existing application version remains `versionCode = 15` / `versionName = "1.3.7"` because this is the same unmerged feature bump.

Non-goals: alter `MIGRATION_9_10`; reintroduce or expose variants; change entities/DAOs/repositories/domain/UI/navigation/state/events, Hilt scopes, dispatcher/cancellation ownership, workers, permissions, remote Sheets, or background behavior; downgrade any database; use destructive fallback; or mutate the emulator database during planning/testing.

Assumptions: the extracted emulator schema is authoritative for v11. Its delta from the pre-revert v9 variant shape is: `exercise_variants.selectionKey TEXT` is nullable with unique index `(exerciseId, selectionKey)`, and `exercise_variant_muscles(variantSyncId, muscle, contribution)` has a composite primary key and FK to `exercise_variants.syncId`; `routine_exercises` and `workout_exercises` retain the v9 variant columns. All variant and muscle metadata is intentionally discarded at v12; base rows and sets are retained.

### Acceptance criteria

- **AC-006:** an exact file-backed emulator-v11 fixture opens through the production Room migration list at v12; no startup migration crash remains.
- **AC-007:** `MIGRATION_11_12` atomically preserves all non-variant routine/workout fields, every `sectionId`, and every completed/incomplete `workout_sets` row (including IDs, parent IDs, indices, metric values and `completedAt`) while dropping both variant tables.
- **AC-008:** the production migration list supports 1…9→10→12, 10→12, and 11→12; `MIGRATION_9_10` remains byte-for-byte/behaviorally unchanged and `MIGRATION_10_12` is an explicitly registered no-op used for Room validation.
- **AC-009:** Room v12 validates the committed generated `12.json`; final tables, foreign keys and indexes match the base-only v10 contract, no variant tables/columns survive, and `PRAGMA foreign_key_check` is empty.
- **AC-010:** no UI/domain/DI/background/permission/version contract changes are introduced; targeted migration gates and final project gates pass on the stable implementation diff, with version held at 15 / 1.3.7.

### Current → target data/execution flow

Current failure path: a persisted v11 file reaches a v10 `GymDatabase` builder whose registered migrations end at 10, so Room cannot validate/open it. The v11 file still owns `exercise_variants`, its nullable `selectionKey` uniqueness constraint, `exercise_variant_muscles`, v9 routine/workout variant columns, and dependent workout sets.

Target path: the same Room SSOT is opened at v12. A v10 database takes registered no-op `10→12` and validates the unchanged base schema. A v11 database takes one transaction `11→12`: drop `exercise_variant_muscles`, rebuild base-shape `routine_exercises`, back up all sets, rebuild base-shape `workout_exercises` preserving `sectionId`, restore every set, then drop `exercise_variants`. Room flows, repository/domain boundaries, immutable ViewModel state/events, UI loading/empty/error/content behavior, navigation/restoration, Hilt bindings/scopes, compute dispatcher/cancellation ownership, workers and permissions are unchanged because this extension has no behavior-layer surface.

### Frozen architectural decisions and contracts

1. **Version bridge and one source of migration truth.** Set `GymDatabase.version = 12`. In `GymDatabase`, expose one ordered `ALL_MIGRATIONS: Array<Migration>` containing every supported production migration, including unchanged `MIGRATION_9_10`, `MIGRATION_10_12`, and `MIGRATION_11_12`. `DataModule` must call `addMigrations(*GymDatabase.ALL_MIGRATIONS)`, and every Room-open test must use that exact array; tests must never register 11→12 separately or reconstruct a near-copy. Do not synthesize an unsupported 10→11 bridge. Room's shortest valid production paths are 1…9→10→12, 10→12, and 11→12.
2. **v10 validation bridge and v9 regression lock.** `MIGRATION_10_12` is `Migration(10, 12)` with no SQL: v10 and v12 entity schemas are deliberately identical. It must nevertheless be registered and exercised by a file-backed Room-open test so Room performs its normal expected-schema validation. Before and after implementation, audit the named `MIGRATION_9_10` source range against `e8ab4fa`; the v9→10 SQL/body is unchanged. A named file-backed v9→10→12 preservation test retains the original migration evidence rather than relying only on 1→12.
3. **v11 atomic preservation map.** In one explicit transaction, first drop `exercise_variant_muscles` (its FK references variants); rebuild `routine_exercises` as `id/routineId/exerciseId/position/restSeconds/plannedSetsJson` with the existing routine/exercise FKs and indexes; copy only those columns; back up `workout_sets`; rebuild `workout_exercises` as `id/workoutId/exerciseId/sectionId/position`, preserving the nonblank `sectionId` check, workout/exercise FKs and its three indexes including unique `sectionId`; restore sets by explicit full column list; then drop `exercise_variants`. Do not depend on `SELECT *` ordering for durable preservation.
4. **v11 exact fixture and external-copy contract.** Tests define the extracted v11 `exercise_variants` DDL with nullable `selectionKey`, its unique `(exerciseId, selectionKey)` index, and exact `exercise_variant_muscles` composite PK/FK DDL, alongside the v9 routine/workout variant columns. Seed rows covering nullable and non-null `selectionKey`, muscles, routine/workout variant references, multiple sections, and completed/incomplete sets. Every fixture opens a real named on-disk database via `GymDatabase.ALL_MIGRATIONS`, not by direct migration invocation alone. Separately, a locally run external-copy gate migrates only a consistent temporary copy of emulator `gym.db` (plus `gym.db-wal`/`gym.db-shm` if present) through that exact array: stop the app process or obtain checkpoint-safe state first, record local `PRAGMA user_version` and `sqlite_master` provenance before migration, never write the source emulator DB or commit its copy, and delete the temporary directory after the evidence is recorded. Root inspection found the crashed process already ended and no WAL/SHM sidecars present; this is provenance, not permission to alter the source.
5. **No behavior expansion.** No entity or DAO changes are permitted for this bridge. Therefore SSOT, repository/domain APIs, immutable UI state/events, Hilt scopes, navigation/SavedState restoration, dispatcher/structured cancellation, background work, permissions, accessibility and adaptive UI all retain the completed v10 feature contracts. There are no new loading/empty/error/content states to test.
6. **Rollback/data preservation.** A future code rollback must still include a forward-compatible migration from v12; Android must never downgrade the user database. v12 intentionally and irreversibly removes variant/muscle metadata while preserving base workout/routine/set data. Recommend backup before update; no remote data is changed.

### Tasks

### T-006 — freeze and implement the v12 Room recovery bridge

- **Owner:** implementation writer (exclusive Room/schema/DataModule choke-point owner)
- **Dependencies:** T-001–T-005 complete; frozen v10 base contract
- **Files:** `app/src/main/java/com/valerochka1337/valerochkagym/data/db/GymDatabase.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/di/DataModule.kt`, `app/schemas/com.valerochka1337.valerochkagym.data.db.GymDatabase/12.json`.
- **Actions:** raise only Room to v12; define the sole ordered `GymDatabase.ALL_MIGRATIONS` production array and make `DataModule` consume it with spread; leave the exact `MIGRATION_9_10` source range unchanged from `e8ab4fa`; add registered `MIGRATION_10_12` with no SQL; add transactional `MIGRATION_11_12` under the frozen DDL/preservation order. Recreate expected base-only FKs/check/indexes and export/commit `12.json`. Do not touch version, entities, DAOs or any non-Room behavior layer.
- **Automated verification:** `git diff --unified=0 e8ab4fa -- app/src/main/java/com/valerochka1337/valerochkagym/data/db/GymDatabase.kt`; `./gradlew :app:testDebugUnitTest --tests "*Migration9To12Test" --tests "*Migration10To12Test" --tests "*Migration11To12Test" --tests "*Migration1To12Test"`.
- **Done:** `DataModule` and every Room-open test consume the same `ALL_MIGRATIONS`; database v12 opens through registered 10→12 and 11→12 paths; named `MIGRATION_9_10` range has no diff versus `e8ab4fa`; committed `12.json` validates and no destructive fallback exists.
- **AC:** AC-006, AC-008, AC-009.

### T-007 — prove exact emulator-v11 recovery and supported paths

- **Owner:** implementation writer (exclusive migration-test owner)
- **Dependencies:** T-006
- **Files:** add/update `app/src/test/java/com/valerochka1337/valerochkagym/data/db/Migration9To12Test.kt`, `Migration10To12Test.kt`, `Migration11To12Test.kt`, `Migration1To12Test.kt`, `EmulatorV11CopyMigrationTest.kt`; update legacy migration test production-list helpers only to replace locally reconstructed lists with `GymDatabase.ALL_MIGRATIONS`.
- **Actions:** create file-backed v9, v10 and exact-emulator-v11 fixtures. The v11 fixture must use the specified nullable `selectionKey` and unique index plus `exercise_variant_muscles` PK/FK DDL, retain v9 routine/workout variant columns, and seed all preservation cases. Every Room-open uses `GymDatabase.ALL_MIGRATIONS`; no test may separately add `MIGRATION_11_12` or reconstruct the production list. Include a named v9→10→12 preservation test. Assert Room validation, table/column removal, expected base FKs/indexes, empty `foreign_key_check`, and exact routine/workout/section/set data preservation. Add an opt-in local external-copy test: after the emulator app is stopped/checkpoint-safe, copy `gym.db` and every present WAL/SHM sidecar into a `mktemp -d` directory with their names intact; before opening the copy, save `PRAGMA user_version` and `SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name` to the task evidence; pass only the copied main-file path to the test, which opens/migrates it with `ALL_MIGRATIONS`; never write/push/commit the source or copy; delete the explicit temporary directory on both pass and failure with a test `finally`/shell trap.
- **Automated verification:** `rg -n 'addMigrations\\(' app/src/test/java` (each Room-open must spread `GymDatabase.ALL_MIGRATIONS`); `./gradlew :app:testDebugUnitTest --tests "*Migration9To12Test" --tests "*Migration10To12Test" --tests "*Migration11To12Test" --tests "*Migration1To12Test" --tests "*Migration*To10Test"`; local opt-in evidence command: `VALEROCHKA_GYM_DB_COPY=/absolute/temp/gym.db ./gradlew :app:testDebugUnitTest --tests "*EmulatorV11CopyMigrationTest"` after provenance capture and before temporary-directory cleanup.
- **External-copy procedure/evidence:** when a real emulator copy is required, first record that its app process is stopped (root's prior inspection found it already crashed/ended); otherwise stop it before copying. Use a newly-created, explicit temporary directory and a cleanup trap, never an in-place source path:

  ```bash
  TASK_TMP=$(mktemp -d)
  trap 'rm -rf "$TASK_TMP"' EXIT
  adb shell am force-stop com.valerochka1337.valerochkagym
  adb exec-out run-as com.valerochka1337.valerochkagym cat databases/gym.db > "$TASK_TMP/gym.db"
  if adb shell run-as com.valerochka1337.valerochkagym test -f databases/gym.db-wal; then adb exec-out run-as com.valerochka1337.valerochkagym cat databases/gym.db-wal > "$TASK_TMP/gym.db-wal"; fi
  if adb shell run-as com.valerochka1337.valerochkagym test -f databases/gym.db-shm; then adb exec-out run-as com.valerochka1337.valerochkagym cat databases/gym.db-shm > "$TASK_TMP/gym.db-shm"; fi
  sqlite3 "$TASK_TMP/gym.db" 'PRAGMA user_version; SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name;' > "$TASK_TMP/provenance.txt"
  VALEROCHKA_GYM_DB_COPY="$TASK_TMP/gym.db" ./gradlew :app:testDebugUnitTest --tests "*EmulatorV11CopyMigrationTest"
  ```

  Copy a WAL/SHM sidecar only when it exists; retain `provenance.txt` only in approved task evidence, never Git; the trap removes the copied database and sidecars on success or failure. The test itself opens/migrates the copy, not the emulator source, using `ALL_MIGRATIONS`.
- **Done:** tests prove v9→10→12 preservation, v10 no-op validation, exact seeded v11 recovery and full historical path via the shared array; local copy evidence records provenance and proves Room opens the migrated copy; no direct-only or separately registered migration test is accepted as sole evidence, and no emulator/user database artifact remains outside the removed temporary directory.
- **AC:** AC-006, AC-007, AC-008, AC-009.

### T-008 — retain version and run the recovery validation gates

- **Owner:** implementation writer (single stable-diff verifier)
- **Dependencies:** T-006, T-007
- **Files:** `app/build.gradle.kts` (inspection only unless already needed to retain `15` / `1.3.7`), `vibe/revert-exercise-variants-plan-track.md`.
- **Actions:** confirm the unmerged-feature version remains exactly 15 / 1.3.7; run the targeted Room suite, then once on the stable application diff run final gates sequentially. Record outcomes and any deviation; do not open/modify the emulator database, alter application layers, commit, push, merge, or create/switch/delete branches/worktrees.
- **Automated verification:** `./gradlew :app:testDebugUnitTest --tests "*Migration9To12Test" --tests "*Migration10To12Test" --tests "*Migration11To12Test" --tests "*Migration1To12Test"`; then `./gradlew :app:testDebugUnitTest`; then `./gradlew :app:assembleDebug`.
- **Done:** all commands are recorded as pass (or a concrete blocker), version remains 15 / 1.3.7, and the diff has no UI/domain/DI/background/permission changes.
- **AC:** AC-010.

### File ownership and execution waves

| Owner | Exclusive boundary |
|---|---|
| Implementation writer | T-006–T-008. Owns Room schema/migrations, `DataModule` registration, generated schema and all migration tests as one serialized unit; no parallel implementer. |
| Planner | Only the two existing `vibe/revert-exercise-variants-*` documents. |

| Wave | Tasks | Rule |
|---|---|---|
| 6 | T-006 | Freeze v12 schema and migration contracts before tests; one owner retains Room/DataModule choke points. |
| 7 | T-007 | Build exact-DDL file-backed tests against the frozen bridge. |
| 8 | T-008 | Run targeted then final sequential gates on the stable diff. |

### Conditional Android quality gates and final project gates

Relevant: handwritten migrations, generated schema export, named v9→10→12 plus incremental/full file-backed Room-open tests through the one production array, a local external-copy recovery gate with provenance and cleanup, FK/index/row preservation assertions, source-range audit of `MIGRATION_9_10` against `e8ab4fa`, and Hilt compilation implied by `DataModule` registration. No UI change means no new Compose/accessibility/adaptive/navigation/render gate; no repository/domain, dispatcher, worker/service, permission, manifest, dependency, release-sensitive or remote-contract gate applies. No release build is required because dependencies/build/release configuration do not change.

After the stable implementation diff, run once and in order:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

### Risks, unresolved questions, rollback/data preservation

- **Risk:** registering only 10→12 leaves v11 installed files unable to open. **Mitigation:** production list includes direct 11→12 and an exact-DDL, file-backed Room-open regression test.
- **Risk:** v11's muscle FK blocks dropping variants or table rebuild loses sets. **Mitigation:** fixed transactional order (muscles first; set backup before workout rebuild; explicit restore; variants last), `foreign_key_check`, and exact row assertions.
- **Risk:** an accidental v10 schema change makes a no-op invalid. **Mitigation:** Room validates `MIGRATION_10_12` against generated `12.json` through a real Room builder.
- **Risk:** a migration test's simplified v11 DDL misses the actual crash shape. **Mitigation:** retain the emulator-extracted nullable `selectionKey` / unique index and muscles PK/FK DDL verbatim in the fixture.
- **Risk:** a test-only migration list proves a route never used by the app. **Mitigation:** one `GymDatabase.ALL_MIGRATIONS` array is spread into `DataModule` and passed to every Room-open test; no test independently adds 11→12.
- **Risk:** copying only `gym.db` while a WAL exists yields an inconsistent recovery input or leaks user data. **Mitigation:** stop/checkpoint-safe the app first; copy all present `gym.db`, `-wal`, `-shm` files read-only into an explicit temp directory, record local SQLite provenance, migrate only that copy, do not commit it, and remove it via `finally`/trap. At the recorded root inspection, the crashed process had ended and no WAL/SHM existed.
- **Unresolved blocker:** none. The emulator database remains source-read-only; the external-copy gate is local opt-in evidence and must clean up its copy.

### Gate P self-check — recovery extension

Pass: AC-006→T-006/T-007 with exact seeded and external-copy v11 Room-open evidence through `ALL_MIGRATIONS`; AC-007→T-006/T-007 with transactional preservation and full row/FK assertions; AC-008→T-006/T-007 with source-range audit unchanged from `e8ab4fa`, shared production array, registered 10→12/11→12 and named v9→10→12/1…9→10→12 tests; AC-009→T-006/T-007 with generated `12.json`, Room validation, schema/index/FK checks; AC-010→T-008 with exact version inspection and targeted/final commands. One implementation writer owns every Room schema/DAO/migration/DataModule/test choke point, task dependencies freeze contracts before test implementation, each task has a command and observable done condition, no task has overlapping ownership, and only applicable conditional gates are listed.
