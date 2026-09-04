# Muscle load system — plan tracker

Status key: `pending | in_progress | done | blocked`. The table below reflects the completed
implementation, independent verification, and Root-owned final gates.

## Task status

| Task | Status | Owner | Dependencies | Done evidence |
|---|---|---|---|---|
| T-001 | done | Implementation writer | — | v13 entity/migration/schema, legacy-zero vs explicit-zero and DAO multi-primary/stabilizer checks green (`Migration12To13Test`, `ExerciseMuscleDaoTest`). |
| T-002 | done | Implementation writer | T-001 | Explicit static 263-row registry keeps historic `builtInExerciseSyncId(name)` for every legacy entry, uses key-derived IDs only for new entries, and has a complete legacy bridge (`CanonicalExerciseRegistryTest`, `Migration7To8Test`). |
| T-003 | done | Implementation writer | T-001,T-002 | Registry-only reconcile is idempotent and preserves legacy local ID, workout history and gym links; unmapped custom absent maps and explicit custom stabilizers remain authoritative (`ExerciseMuscleDaoTest`). |
| T-004 | done | Implementation writer | T-001,T-003 | Custom-only export skips registry built-ins; import preserves local built-ins, converts legacy CHEST/numeric roles, keeps explicit stabilizers, and continues after an invalid map (`ConfigurationSheetsRepositoryTest`, `WorkoutImportRepositoryTest`, `ExerciseSheetRowsTest`). |
| T-005 | done | Implementation writer | T-001 | role weighting, cardio boundary and chest per-set maximum green (`AnalyticsEngineTest`). |
| T-006 | done | Implementation writer | T-001,T-005,T-007,T-008 | VM rejects no-primary/non-role writes and copy-on-personalize creates a new custom identity without changing the built-in source map (`ExerciseLibraryViewModelTest`). |
| T-007 | done | Implementation writer | T-001 | AI schema/prompt/parser require canonical roles and a primary; focused generator regressions green (`AiApiExerciseAiGeneratorTest`). |
| T-008 | done | Implementation writer | T-001 | 25-item accessible finite cyclic selector, off-geometry coverage and body-map hit coverage green (`BodyMapHitTest`). |
| T-009 | done | Implementation writer | T-005,T-008 | analysis role text/selector alternative and render/VM checks green (`AnalysisRenderTest`, `AnalysisViewModelTest`); snapshots uninspected. |
| T-010 | done | Implementation writer | T-001,T-005 | A migration-created Room marker plus completed-history query delivers the notice once, redelivers before acknowledgement, and stays absent on fresh/active-only installs (`MuscleLoadUpgradeNoticeTest`, `AnalysisViewModelTest`). |
| T-011 | done | Implementation writer | T-001–T-010 | version is exactly 16/1.3.8; active-workout regression green (`ActiveWorkoutScreenTest`). |
| T-012 | done | Root | T-001–T-011 | Full unit suite and debug assembly are green after resolving the seven regressions exposed by the first full-suite attempt. |

## AC → task → verification traceability

| AC | Task(s) | Verification |
|---|---|---|
| 001 | T-005,T-006,T-009 | analytics, editor/detail, analysis UI tests |
| 002 | T-005 | `AnalyticsEngineTest` strength/timed roles |
| 003 | T-005 | `AnalyticsEngineTest` cardio boundary |
| 004 | T-001,T-004,T-006,T-007 | migration/round-trip/editor/AI multi-primary + explicit stabilizer tests |
| 005 | T-005 | per-set upper/lower chest max test |
| 006 | T-008 | BodyMap hit + selector synchronization test |
| 007 | T-008 | centered selector settle/neighbor tests |
| 008 | T-008 | geometry auto-side/off-geometry tests |
| 009 | T-008 | semantics, 48dp, fontScale/adaptive tests |
| 010 | T-006 | built-in read-only/personalize VM test |
| 011 | T-006 | four-state editor test |
| 012 | T-006 | no-primary rejection/no-write test |
| 013 | T-007 | canonical valid AI draft test |
| 014 | T-007 | invalid/network no-partial-write test |
| 015 | T-002,T-003 | registry legacy-identity/reconciler test |
| 016 | T-002 | catalog matrix/count/duplicate coverage test |
| 017 | T-003,T-004 | canonical built-in reconciliation/import test |
| 018 | T-001 | incremental/full migration conversion test |
| 019 | T-001,T-006 | CHEST migration/review-clear test |
| 020 | T-001,T-005,T-010,T-011 | protected-row migration + recalculation + once-only notice + regression test |
| 021 | T-004 | legacy Sheets and invalid future map import tests |
| 022 | T-003 | untouched `gym_exercises` test |
| 023 | T-006,T-008,T-009 | role text and semantic description tests |
| 024 | T-010,T-011 | notice isolation + active workout regression test |

## Command results

| Command | Result | Notes |
|---|---|---|
| Planning inspection only | not run | Documentation-only change; AGENTS prohibits Gradle for plans. |
| `./gradlew :app:testDebugUnitTest --tests '*Migration12To13Test' --tests '*Migration1To13Test' --tests '*ExerciseMuscleDaoTest'` | pending | T-001 |
| `./gradlew :app:testDebugUnitTest --tests '*Migration12To13Test' --tests '*CanonicalExerciseRegistryTest'` | pass | Focused v13 DML/schema and registry gate, 2026-09-04. |
| `./gradlew :app:testDebugUnitTest --tests '*CanonicalExerciseRegistryTest'` | pass | Static-registry corrective P1 gate, 2026-09-04; BUILD SUCCESSFUL (pre-existing deprecated `Muscle.CHEST` test warnings only). |
| `./gradlew :app:testDebugUnitTest --tests '*Migration12To13RoomTest' --tests '*MuscleLoadUpgradeNoticeTest'` | pass | Exact exported-v12 schema via `MigrationTestHelper`, Room target-schema/protected-row validation, and bound Room-notice gate, 2026-09-04. |
| `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` | pass | Targeted production/test compilation gate, 2026-09-04. |
| `./gradlew :app:testDebugUnitTest --tests '*AnalyticsEngineTest.upper*' --tests '*ExerciseSheetRowsTest.legacy*'` | pass | Focused per-set chest maximum and legacy Sheets conversion gate, 2026-09-04. |
| `./gradlew :app:testDebugUnitTest --tests '*ExerciseSheetRowsTest' --tests '*AnalyticsEngineTest' --tests '*Migration12To13Test' --tests '*CanonicalExerciseRegistryTest'` | pass | Consolidated T-001/T-002/T-004/T-005 targeted boundary, 2026-09-04. |
| `./gradlew :app:testDebugUnitTest --tests '*ExerciseSheetRowsTest' --tests '*Migration12To13Test'` | pass | Legacy CHEST zero-as-absence parser and v13 migration regression, 2026-09-04. |
| `./gradlew :app:testDebugUnitTest --tests '*Migration12To13Test' --tests '*CanonicalExerciseRegistryTest' --tests '*ExerciseMuscleDaoTest' --tests '*ExerciseSheetRowsTest' --tests '*AnalyticsEngineTest' --tests '*AiApiExerciseAiGeneratorTest' --tests '*AnalysisRenderTest' --tests '*AnalysisViewModelTest' --tests '*BodyMapHitTest' --tests '*ActiveWorkoutScreenTest'` | pass | Consolidated targeted Gate I boundary; render snapshots intentionally uninspected, 2026-09-04. |
| `./gradlew :app:testDebugUnitTest --tests '*ExerciseMuscleDaoTest' --tests '*ConfigurationSheetsRepositoryTest' --tests '*WorkoutImportRepositoryTest' --tests '*ExerciseLibraryViewModelTest' --tests '*AnalysisViewModelTest' --tests '*MuscleLoadUpgradeNoticeTest'` | pass | Consolidated T-003/T-004/T-006/T-010 regression gate, 2026-09-04. |
| `./gradlew :app:testDebugUnitTest --tests '*BodyMapHitTest' --tests '*AnalysisViewModelTest' --tests '*CanonicalExerciseRegistryTest' --tests '*ExerciseMuscleDaoTest'` | pass | Cyclic snap-selector wrap/center/recenter, same-selection VM, registry pattern and reconciliation boundary, 2026-09-04. |
| `./gradlew :app:testDebugUnitTest --tests '*ExerciseSheetRowsTest' --tests '*ConfigurationSheetsRepositoryTest' --tests '*WorkoutImportRepositoryTest' --tests '*BodyMapHitTest' --tests '*AnalysisViewModelTest'` | pass | Legacy A:I header upgrade to J=2, zero-only snapshot preservation, import continuity and centralized selector selection boundary, 2026-09-04. |
| `./gradlew :app:testDebugUnitTest` | failed | Root's first final-gate attempt found stale percentage/identity/icon assertions after the role-registry migration; T-012 remains Root-owned and pending. |
| `./gradlew :app:testDebugUnitTest --tests '*ConfigurationImportRepositoryTest' --tests '*Migration7To8Test' --tests '*MuscleDefaultsTest' --tests '*SeedExerciseMusclesTest' --tests '*ExerciseIconTest' --tests '*CanonicalExerciseRegistryTest' --tests '*ExerciseMuscleDaoTest'` | pass | 41 tests pass, 2026-09-04: canonical 100/50/0 fixtures, historical legacy IDs, full registry-derived seed-map parity, descriptive cardio roles, and all catalog icon fallbacks. The first rerun exposed one additional stale DAO expectation (`20`→`50`), corrected before this passing rerun. |
| Targeted seed/reconcile/import/analytics/editor/AI/body/analysis/active test commands in plan | pass | Covered by the focused and consolidated commands above. |
| `./gradlew :app:testDebugUnitTest` | pass | Final stable-diff rerun after corrective fixes: BUILD SUCCESSFUL in 15s (772 tests discovered; 1 skipped). |
| `./gradlew :app:assembleDebug` | pass | Final debug assembly: BUILD SUCCESSFUL in 6s. |

## Deviations

- The planned DataStore notice heuristic was replaced by a migration-created Room marker. This is
  required to distinguish a real v12→v13 upgrade from a fresh v13 install and is covered by exact
  migration plus bound-repository tests; user-visible behavior is unchanged.

## Findings

- The baseline was Room v12 with 18 muscle IDs and threshold percentages; the implemented target is
  Room v13 with 25 IDs, canonical role values, review provenance and the upgrade marker.
- Legacy v12 zero means absence; new explicit zero means stabilizer. The migration is the sole
  boundary that removes zero rows.
- Legacy A:I Sheets input remains supported; canonical output safely upgrades the header to A:J and
  writes `model_version=2`, which disambiguates legacy zero-as-absence from explicit stabilizer zero.
- Existing `BodyMapFlip`/`BodyMap`, seeding, migration tests, render test, and compute-dispatcher
  analytics are extension points; preserve them rather than adding a parallel system.
- P1 catalog finding resolved: the registry is a committed row-per-exercise DSL with explicit
  movement pattern, equipment/family coverage, and legacy bridge fields; runtime expansion and
  name/group pattern inference were removed. Colliding alternatives were dropped, retaining the
  original explicit row and leaving 263 unique reviewed entries.
- Independent Gate T and Gate V rechecks report no remaining P0/P1/P2 findings. The first full-suite
  failures were resolved by restoring legacy built-in sync identities, projecting all 263 seed maps
  from the canonical registry, updating role-based fixtures, and covering the expanded cardio icons.

## Residual risks

- Catalog coverage tests cannot prove disputed biomechanics; use conservative secondary roles.
- A legacy CHEST custom map is intentionally approximate until user review; chest aggregation max
  prevents doubled volume.
- Downgrade after v13 requires backup restore; never add destructive fallback.
