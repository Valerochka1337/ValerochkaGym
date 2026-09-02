# Exercise execution variants — tracker

Status vocabulary: `pending` | `in_progress` | `done` | `blocked`.

| Task | Status | Owner | Depends on | ACs | Planned verification |
|---|---|---|---|---|---|
| T-001 version bump | done | Implementation writer | — | release evidence | `:app:assembleDebug` |
| T-002 Room v9 and migration | done | Implementation writer | T-001 | 011–014 | migration + DAO tests: composite routine FK and section snapshot invariant |
| T-003 execution key/repository | done | Implementation writer | T-002 | 004–011,019 | active/gym/routine-update/previous/PR targeted tests |
| T-004 Sheets durable formats | blocked | Implementation writer | T-002,T-003 | 015–019 | legacy/current/corrupt header, ordinary retry, mapper/parser/import/worker tests; PD-013 accepted lost-response insertion risk |
| T-005 variant management | done | Implementation writer | T-002,T-003 | 012,013,019,020 | detail VM/Compose tests |
| T-006 routine selection | done | Implementation writer | T-002,T-003,T-005 | 001–004,012,020 | routine/library VM/Compose tests |
| T-007 active selection | blocked | Implementation writer | T-003,T-006 | 001–008,011,019,020 | named, explicit-none and catalog/default-none active/nav restoration tests |
| T-008 history/statistics displays | blocked | Implementation writer | T-003,T-007 | 005,008–012,017,020 | detail-group restoration and mixed-group card-label tests |
| T-009 cross-layer coverage | blocked | Implementation writer | T-002…T-008 | 001–020 | targeted migration/import/UI tests, including new token/label coverage |
| T-010 final gates/tracker | done | Main agent | T-001…T-009 | 001–020 | full unit tests, debug assembly |

## AC → task → test traceability

| AC | Implementing tasks | Required automated evidence |
|---|---|---|
| AC-001 | T-006,T-007 | `RoutineEditorScreenTest`, `ActiveWorkoutScreenTest` |
| AC-002 | T-006,T-007 | `RoutineEditorViewModelTest`, `ActiveWorkoutViewModelTest` |
| AC-003 | T-006,T-007 | routine/active VM cancellation tests |
| AC-004 | T-003,T-007 | `ActiveWorkoutRepositoryTest`, `RoutineUpdateUseCaseTest` (repeated rows preserve variant/rest) |
| AC-005 | T-003,T-008 | `PreviousSetsUseCaseTest`, statistics tests |
| AC-006 | T-003,T-007 | repository transaction + active VM tests |
| AC-007 | T-003,T-007 | repository lock + active Compose semantics test |
| AC-008 | T-007,T-008,T-009 | active/detail/summary/history tests for named, explicit-none and catalog/default-none restoration |
| AC-009 | T-003,T-008 | `WorkoutStatsUseCaseTest`, `ExerciseDetailViewModelTest` |
| AC-010 | T-008 | `WorkoutStatsUseCaseTest` |
| AC-011 | T-002,T-008 | DAO/statistics/history VM tests |
| AC-012 | T-002,T-005,T-006,T-008 | DAO/detail/routine/history tests |
| AC-013 | T-002,T-005 | repository + detail VM tests |
| AC-014 | T-002 | `Migration8To9Test`, `Migration1To9Test` (fresh section UUID/null tuple) |
| AC-015 | T-004 | `RoutineRowParserTest`, `WorkoutImportRepositoryTest`, legacy/current/corrupt header tests (not lost-response idempotence) |
| AC-016 | T-004 | mapper/parser/import completed-set round-trip with UUID/position/snapshot test |
| AC-017 | T-004,T-008 | import + history/detail VM test |
| AC-018 | T-004 | transactional `WorkoutImportRepositoryTest` |
| AC-019 | T-003,T-004,T-005,T-007 | repository/worker/VM tests using local fakes |
| AC-020 | T-005,T-006,T-007,T-008 | detail/routine/active semantics & `fontScale=2.0` Compose tests |

## Deviations

The v8→v9 parent rebuild explicitly snapshots and restores `workout_sets`: SQLite's foreign-key
mode differs between direct migration and Room-open paths, so the child table is cleared before the
exact backup is restored to avoid both cascade loss and duplicate primary keys. This preserves all
completed and incomplete legacy rows. This is an implementation hardening, not an acceptance-criteria
change.

## Findings

Product decisions PD-011…013 supersede the remaining strict-review disposition:

- T-007/T-008/T-009/T-010 are reopened for an explicit `none` execution-group token, named/none/default restoration coverage, and mixed-group exercise/PR labels.
- PD-013 accepts the lost-response column-insert risk, so T-004 is done; ordinary retry, legacy/current/corrupt headers and import rollback remain required.

Gate T/V are pending the reopened UI/navigation/label tasks, not blocked on Sheets insertion idempotence.

Consolidated fix: current `Workouts!A:S` is now header-aware and strict. A current row needs
canonical section and exercise UUIDs, a complete UUID/snapshot variant tuple, and a consistent
tuple for every row of one section; violations return an import failure before local writes.
Legacy headers retain their tolerant explicit-none parsing.

Final parser hardening: any 19-column `Workouts` header must exactly equal the managed A:S
header. Current rows also require a nonnegative integer `section_position` that remains constant
inside a section. Bad headers or positions fail before the import transaction, while true legacy
headers remain supported.

Gate T found no remaining production P0/P1. Strict Gate V still fails on acceptance evidence that
was requested in both fix passes but was not added: actionable named/none chooser semantics at
`fontScale=2.0`, a mixed named/none workout with a single labelled PR, and current `Routines!A:M`
import with a populated valid variant plus wrong-owner rollback. Per the two-pass stop rule, T-004,
T-007, T-008 and T-009 remain blocked on direct evidence. The owner subsequently requested commit
and push of the current implementation; final full gates passed before publication.

## Command results

Not run by planning task (documentation-only change): per `AGENTS.md`, no Gradle test or build is required or authorized here.

| Command | Result | Date |
|---|---|---|
| — | Pending implementation | — |
| `./gradlew :app:compileDebugKotlin` | PASS | 2026-09-02 |
| `./gradlew :app:testDebugUnitTest --tests '*Migration8To9Test' --tests '*WorkoutRowMapperTest' --tests '*RoutineRowParserTest' --tests '*WorkoutRowParserTest'` | PASS | 2026-09-02 |
| `./gradlew :app:testDebugUnitTest --tests '*SheetsRepositoryTest'` | PASS | 2026-09-02 |
| `./gradlew :app:testDebugUnitTest --tests '*ActiveWorkoutViewModelTest' --tests '*ActiveWorkoutScreenTest' --tests '*ExerciseDetailViewModelTest' --tests '*SheetsRepositoryTest' --tests '*WorkoutImportRepositoryTest'` | PASS | 2026-09-02 |
| `./gradlew :app:testDebugUnitTest --tests '*RoutineEditorViewModelTest' --tests '*RoutineEditorScreenTest' --tests '*RoutineDaoTest'` | PASS | 2026-09-02 |
| `./gradlew :app:testDebugUnitTest --tests '*AnalyticsEngineTest' --tests '*AnalysisViewModelTest' --tests '*WorkoutStatsUseCaseTest'` | PASS | 2026-09-02 |
| `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` | PASS | 2026-09-02 |
| `./gradlew :app:testDebugUnitTest --tests '*Migration8To9Test' --tests '*Migration1To9Test' --tests '*Migration2To3Test' --tests '*Migration3To4Test' --tests '*WorkoutImportRepositoryTest'` | PASS | 2026-09-02 |
| `./gradlew :app:testDebugUnitTest --tests '*ActiveWorkoutViewModelTest' --tests '*ActiveWorkoutScreenTest' --tests '*RoutineEditorViewModelTest' --tests '*RoutineEditorScreenTest' --tests '*ExerciseDetailViewModelTest' --tests '*ExerciseDetailScreenTest' --tests '*WorkoutStatsUseCaseTest' --tests '*AnalyticsEngineTest'` | PASS | 2026-09-02 |
| `./gradlew :app:testDebugUnitTest --tests '*ExerciseVariantSheetRowsTest' --tests '*SheetsRepositoryTest' --tests '*WorkoutRowMapperTest' --tests '*WorkoutRowParserTest' --tests '*RoutineRowParserTest'` | PASS | 2026-09-02 |
| `./gradlew :app:testDebugUnitTest --tests '*ExerciseDetailViewModelTest' --tests '*WorkoutSummaryViewModelTest' --tests '*WorkoutDetailViewModelTest' --tests '*AnalysisViewModelTest' --tests '*AnalyticsEngineTest' --tests '*SheetsRepositoryTest' --tests '*ActiveWorkoutViewModelTest' --tests '*ActiveWorkoutScreenTest'` | PASS | 2026-09-02 |
| `./gradlew :app:testDebugUnitTest --tests '*ExecutionGroupTokenTest' --tests '*ExerciseDetailViewModelTest' --tests '*WorkoutSummaryViewModelTest' --tests '*ActiveWorkoutScreenTest' --tests '*AdaptiveNavigationTest'` | PASS | 2026-09-02 |
| `./gradlew :app:testDebugUnitTest --tests '*WorkoutRowParserTest' --tests '*WorkoutImportRepositoryTest' --tests '*ConfigurationImportRepositoryTest' --tests '*ActiveWorkoutScreenTest' --tests '*WorkoutSummaryViewModelTest'` | PASS | 2026-09-02 |
| `./gradlew :app:testDebugUnitTest --tests '*WorkoutRowParserTest' --tests '*WorkoutImportRepositoryTest' --tests '*ExerciseDetailViewModelTest' --tests '*ActiveWorkoutScreenTest'` | PASS | 2026-09-02 |
| `./gradlew :app:testDebugUnitTest --tests '*WorkoutImportRepositoryTest' --tests '*WorkoutRowParserTest' --tests '*RoutineRowParserTest' --tests '*SheetsRepositoryTest' --tests '*ActiveWorkoutScreenTest' --tests '*WorkoutSummaryViewModelTest'` | PASS; Gate T passes, strict Gate V still lacks direct acceptance evidence | 2026-09-02 |
| `./gradlew :app:testDebugUnitTest --tests '*SheetsRepositoryTest' --tests '*ExerciseDetailViewModelTest' --tests '*WorkoutSummaryViewModelTest' --tests '*WorkoutDetailViewModelTest' --tests '*AnalysisViewModelTest' --tests '*AnalysisRenderTest' --tests '*ActiveWorkoutViewModelTest' --tests '*ActiveWorkoutScreenTest' --tests '*RoutineEditorViewModelTest' --tests '*RoutineEditorScreenTest' --tests '*AdaptiveNavigationTest'` | PASS; Gate T still fails on explicit-none navigation and coverage gaps | 2026-09-02 |
| `./gradlew :app:testDebugUnitTest` | PASS; BUILD SUCCESSFUL in 16s | 2026-09-02 |
| `./gradlew :app:assembleDebug` | PASS; BUILD SUCCESSFUL in 5s | 2026-09-02 |

## Residual risks

- Legacy Workouts lacks section identity and therefore remains name-grouped/none by design; new export guarantees identity only for completed sets.
- A manually corrupted header or unknown/wrong-owner routine variant UUID correctly rejects import/append until source data is repaired; local configuration is preserved.
- **Accepted PD-013 risk:** if a Sheets column insertion commits but its response is lost, retry may insert and shift columns a second time. No durable operation marker is added in this scope.
- Direct feature tests are still missing for populated/wrong-owner `Routines!A:M`, mixed-workout
  sole-PR rendering, and actionable chooser semantics at `fontScale=2.0`; the full existing unit
  suite and debug assembly nevertheless pass.
