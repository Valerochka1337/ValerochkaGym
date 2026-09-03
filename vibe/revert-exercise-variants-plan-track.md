# Revert exercise variants — tracker

Status legend: `pending` | `in_progress` | `done` | `blocked`

| Task | Status | Owner | Depends on | AC | Automated evidence | Done evidence |
|---|---|---|---|---|---|---|
| T-001 surgical reverse boundary | done | implementation writer | — | AC-003 | `./gradlew :app:testDebugUnitTest --tests "*WorkoutDaoTest" --tests "*ExerciseCatalogRepositoryImplTest" --tests "*ExerciseCatalogProjectionTest" --tests "*ExerciseLibrary*"` | Symbol-level diff retains PR #25–#27 plus frozen DAO/history/catalog DI contracts. |
| T-002 Room v9→v10 | done | implementation writer | T-001 | AC-002, AC-004 | `./gradlew :app:testDebugUnitTest --tests "*Migration8To9Test" --tests "*Migration9To10Test" --tests "*Migration1To10Test"` | Exact production migration list, v10 schema, FK/indexes and all fixture sets pass. |
| T-003 domain/data/sync reverse | done | implementation writer | T-002 | AC-001, AC-003 | `./gradlew :app:testDebugUnitTest --tests "*WorkoutRow*" --tests "*RoutineRow*" --tests "*ConfigurationImport*" --tests "*WorkoutImport*" --tests "*SheetsRepository*" --tests "*Upload*"` | A:M/A:S fixtures retain base fields/section ID, discard variants, ignore sheet and append safely. |
| T-004 UI/state/navigation reverse | done | implementation writer | T-002, T-003 | AC-001, AC-003, AC-005 | `./gradlew :app:testDebugUnitTest --tests "*ActiveWorkout*" --tests "*RoutineEditor*" --tests "*ExerciseDetail*" --tests "*WorkoutDetail*" --tests "*WorkoutSummary*" --tests "*AnalysisRenderTest"` | Base-only UI/routes; render test runs; snapshots not viewed. |
| T-005 version/final verification | done | root | T-001–T-004 | AC-005 | `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug` | Version is 15/1.3.7; both final gates pass on the stable diff. |
| T-006 v12 Room recovery bridge | done | implementation writer | T-001–T-005 | AC-006, AC-008, AC-009 | `git diff --unified=0 e8ab4fa -- …/GymDatabase.kt`; targeted v12 migrations | `GymDatabase.ALL_MIGRATIONS` is the single production/test source; v12 registers no-op 10→12 and transactional 11→12; named v9→10 source range is byte-identical; generated `12.json` validates. |
| T-007 exact v11 recovery tests | done | implementation writer | T-006 | AC-006, AC-007, AC-008, AC-009 | named v9→12/v10→12/v11→12/1→12 tests; opt-in `EmulatorV11CopyMigrationTest` with `VALEROCHKA_GYM_DB_COPY` | Seeded exact-DDL and source-read-only copied emulator fixtures open only through `ALL_MIGRATIONS`, preserve base rows/sets/FKs/indexes, record provenance, and clean temporary files. |
| T-008 recovery verification/version hold | done | root | T-006, T-007 | AC-010 | targeted v12 migrations; `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug` | Version remains 15/1.3.7; targeted migrations, the full unit suite and debug assembly pass. |

## AC → task → test traceability

| AC | Implementing task(s) | Test/verification |
|---|---|---|
| AC-001 | T-003, T-004 | exact v9 A:M/A:S parser/import/upload/worker fixtures; affected ViewModel/UI suite; no-variant source audit. |
| AC-002 | T-002 | full v9 variant-reference fixture checks all rows/sets, `PRAGMA foreign_key_check` and expected indexes after Room-v10 open. |
| AC-003 | T-001, T-003, T-004 | frozen `WorkoutDaoTest`, catalog repository/projection DI-contract/library tests plus symbol-level overlap audit. |
| AC-004 | T-002 | `Migration8To9Test`, `Migration9To10Test`, `Migration1To10Test`; exact production list appends `MIGRATION_9_10` after unchanged `MIGRATION_8_9`; committed `10.json`. |
| AC-005 | T-004, T-005 | `AnalysisRenderTest` (no snapshot viewing), full unit suite, debug assembly, version/Git checks. |
| AC-006 | T-006, T-007 | exact seeded v11 plus opt-in emulator-copy Room opens through `GymDatabase.ALL_MIGRATIONS`; recorded provenance and cleanup. |
| AC-007 | T-006, T-007 | v11 routine/workout/section/set fixtures, `PRAGMA foreign_key_check`, final-table and exact row assertions. |
| AC-008 | T-006, T-007 | shared `ALL_MIGRATIONS` used by DataModule/tests, source-range audit against `e8ab4fa`, registered 10→12/11→12 and named v9→10→12, 1…9→10→12 tests. |
| AC-009 | T-006, T-007 | committed `12.json` Room validation plus final foreign-key/index/table/column checks. |
| AC-010 | T-008 | version inspection (15 / 1.3.7), targeted Room suite, full unit suite and debug assembly. |

## Deviations

- Final full unit suite and debug assembly ran once in the root session on the stable diff; implementation ran only the required targeted gates. No acceptance criterion or data contract changed.
- Recovery extension is planned against an emulator-extracted v11 DDL only; the root session did not modify the emulator database. This is a forward v11→12 recovery, not a version-bump or product-behavior change.
- Root inspection found the emulator process already crashed/ended and no `gym.db-wal` or `gym.db-shm` sidecars. The planned external-copy gate remains required to recheck this at execution and copy any sidecars that then exist.
- The external v11 source copy had 49 pre-existing `exercise_muscles → exercises` foreign-key violations. The opt-in gate records that baseline and proves v11→v12 creates none; seeded exact-DDL migration fixtures retain an empty `foreign_key_check`.

## Findings

- Baseline is `fix/revert-exercise-variants`; `main` is `8c7d51b` and post-variant catalog/search changes overlap several reverse targets.
- Current database is v9 and app version is 14 / 1.3.6.
- Variant feature entered via merge `f9eb26e`; safe removal requires a forward v9→v10 migration, not a source/database rollback.
- Sheets compatibility is forward-only: existing v9 `Routines` A:M and `Workouts` A:S remain in place; only variant values are discarded locally and `ExerciseVariants` is ignored.
- Post-v10 crash finding: the emulator file has `user_version = 11`, nullable `exercise_variants.selectionKey` with unique `(exerciseId, selectionKey)`, and `exercise_variant_muscles(variantSyncId,muscle,contribution)` PK/FK; the v10 production list has no path to open it.

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
| Recovery-extension planning inspection | pass | Documentation-only plan update; no Gradle command run and emulator DB was not changed. |
| Recovery plan-review revision | pass | Documentation-only: centralized migration-list, named v9→10→12, and source-read-only external-copy evidence/cleanup requirements added; no emulator or Gradle action run. |
| `./gradlew :app:testDebugUnitTest --tests "*Migration9To12Test" --tests "*Migration10To12Test" --tests "*Migration11To12Test" --tests "*Migration1To12Test" --tests "*Migration*To10Test"` | pass | v1/v9/v10/v11 file-backed paths open only through `ALL_MIGRATIONS`; seeded v11 preserves routine/workout/section/completed and incomplete set fields, FKs and indexes. |
| `VALEROCHKA_GYM_DB_COPY=/private/tmp/vgym-db-inspect.1PcZBH/gym.db ./gradlew :app:testDebugUnitTest --tests "*EmulatorV11CopyMigrationTest"` | pass | Provenance: user_version 11; nullable `selectionKey`, unique `(exerciseId, selectionKey)`, and `exercise_variant_muscles` PK/FK found. SHA-256 remained `a8ea495b341207e238f8227805c6e1896679fe8278dbf9edf50e86efd5310762`; the test migrates only its internal copy and carries any existing WAL/SHM sidecars. |
| `git diff --check`; extracted `MIGRATION_9_10` SHA-256 comparison to `e8ab4fa` | pass | No whitespace errors; both extracted migration blocks are `e2874fd2465c2ebee742c356d7395ef5f1b881f0fee6bc7430a4ac93f22fa412`. |
| `./gradlew :app:testDebugUnitTest` | pass | Final full recovery-diff unit suite; `BUILD SUCCESSFUL` in 18s. |
| `./gradlew :app:assembleDebug` | pass | Final recovery-diff debug assembly; `BUILD SUCCESSFUL` in 7s. |

## Residual risks

- v10 deliberately discards local variant metadata while preserving all non-variant records and sets; advise backup before update.
- Existing remote A:M/A:S columns and variant rows are retained remotely; no column is deleted, rewritten or shifted.
- Snapshot visual inspection remains blocked unless the user explicitly asks for it.
- P1 fix passes resolved: existing v9 `Routines` are read through A:M; v9 `Workouts` validates canonical section/base tuples before an import transaction, while R:S variant cells remain ignored; exact legacy `exercise_detail/{exerciseId}/{executionGroup}` navigation state restores into the base-only detail destination.
- The installed emulator database remains v11 until the fixed APK is explicitly installed; the tested v12 migration intentionally discards v11-only variant and muscle metadata but retains all base rows and sets.
- The external-copy gate migrated only an internal test copy; its temporary source directory was removed after the source SHA remained unchanged.
- The provided temporary source had 49 existing `exercise_muscles` orphan rows before migration. This predates and is unrelated to the v11 variant recovery; the external-copy test ensures the migration introduces none, while the deterministic fixture verifies an initially clean database remains clean.
