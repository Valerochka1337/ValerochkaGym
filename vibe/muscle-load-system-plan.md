# Muscle load system — executable plan

## Goal

Replace percentage-based muscle activation with the approved 25-muscle role model
(`PRIMARY=100`, `SECONDARY=50`, `STABILIZER=0`), make it durable and sync-safe, and use it
consistently in the catalog, editor, AI, analytics, search, and accessible body-map selection.

## Scope, non-goals, assumptions

Scope is AC-001…AC-024 in the approved brief.  The existing single `:app` module remains the
only module.  Room is the SSOT for exercises and maps; completed workout sets remain an immutable
log and analytics deliberately reads the *current* map.  `v13` is the sole schema increment and
the feature changes version `15/1.3.7` to `16/1.3.8` exactly once.

Out: exercise variants, per-set technique/RIR adjustments, medical conclusions, automatic gym
membership for new catalog entries, new permissions, telemetry, dependencies, and a mandatory
active-workout action. T-002 creates the reviewable Kotlin canonical registry under the approved
family/equipment criteria; no product decision remains open.

## Acceptance criteria

| AC | Acceptance condition | Task(s) / verification |
|---|---|---|
| AC-001 | Maps show named roles, not percentages | T-006, T-009 / UI unit tests |
| AC-002 | Strength/timed roles contribute 1/.5/0 | T-005 / `AnalyticsEngineTest` |
| AC-003 | Cardio remains descriptive, adds no strength volume | T-005 / `AnalyticsEngineTest` |
| AC-004 | Multiple primaries persist; explicit stabilizer persists | T-001, T-004, T-006, T-007 / migration, round-trip + VM tests |
| AC-005 | Chest/balance uses per-set max | T-005 / analytics boundary tests |
| AC-006 | Body tap centers selector and text | T-008 / Compose semantics test |
| AC-007 | Drag/fling/neighbor tap settles synchronized center | T-008 / selector state tests |
| AC-008 | Auto-side and off-geometry selection work | T-008 / mapping + semantics tests |
| AC-009 | 25 finite accessible choices at 200% font | T-008 / Compose semantics/adaptive test |
| AC-010 | Built-in is read-only; personalize copies | T-006 / VM tests |
| AC-011 | Custom map has four role states | T-006 / VM/screen tests |
| AC-012 | Save without primary is rejected | T-006 / VM test |
| AC-013 | Valid AI is canonical editable draft | T-007 / generator tests |
| AC-014 | AI failure writes no partial data; manual remains | T-007 / generator/VM tests |
| AC-015 | Stable built-ins reconcile without history break | T-002, T-003 / registry + reconciler tests |
| AC-016 | 250–350 deduplicated matrix covers scope | T-002, T-003 / catalog coverage test |
| AC-017 | Built-in canonical maps resist cloud rollback | T-003, T-004 / reconciler/import tests |
| AC-018 | Custom 0–100 maps migrate to roles intact | T-001 / migration tests |
| AC-019 | Legacy CHEST duplicates safely and requests review | T-001, T-006 / migration + VM tests |
| AC-020 | Journal unchanged; history recalculates with one nonblocking notice | T-001, T-005, T-010 / full-path migration + notice test |
| AC-021 | Legacy Sheets converts; bad future map is skipped | T-004 / parser/import tests |
| AC-022 | Reconciled built-ins are not auto-added to gyms | T-003 / reconciler test |
| AC-023 | Body role has text/semantic meaning besides color | T-008, T-009 / semantics test |
| AC-024 | Active workout needs no new recording action | T-010, T-011 / active-workout + notice regression test |

## Current → target flow

`Room exercise_muscles(contribution 0..100) → DAO Flow → projectors/VM → percentage editor,
BodyMap, AnalyticsEngine threshold` becomes `Room canonical role encoding (100/50/0; no row =
not involved) → DAO/repository Flow → immutable UiState + event reducers → role text and shared
BodyMapFlip/selector`.  `AnalyticsEngine` consumes current maps on `@ComputeDispatcher`; for each
completed non-cardio hard set it grants primary `1`, secondary `.5`, stabilizer `0`, then computes
aggregate chest/balance from the maximum upper/lower contribution **within that set**.  No map
snapshot is added to workout sets.

The configuration worker remains deferrable WorkManager work, but exports/imports only custom
exercise maps under LWW by `updatedAt`; built-ins are local catalog authority.  Parsing/conversion
is pure and transactional import applies valid records while returning nonfatal skipped-map detail.
No new permission, service, worker, or scheduling contract is introduced.

## Frozen contracts and decisions

1. `Muscle.entries` is the exact ordered 25-ID registry; roles are an enum/domain value with only
   canonical persistence `100/50/0`: `100=PRIMARY`, `50=SECONDARY`, `0=STABILIZER`; no row is
   `NOT_INVOLVED`. `CHEST` is accepted only by the legacy converter and becomes `UPPER_CHEST` +
   `LOWER_CHEST`. `MIGRATION_12_13` deletes *legacy* zero rows only; it maps legacy `1..24` to
   persisted canonical stabilizer `0`. Thereafter DAO, editor, AI, Sheets parser/export and import
   preserve explicit zero rows, never conflate them with absence.
2. `ExerciseEntity.needsMuscleMapReview: Boolean` is durable, default false. Only migrated custom
   legacy CHEST sets it; a successful custom edit clears it. Built-ins are never editable or
   upload-authoritative; personalization creates a new custom entity/map and retains original IDs,
   sync IDs, history, gym rows, routines, and sets.
3. T-001 owns every schema/change/migration/DAO/exported-schema test. `MIGRATION_12_13` is
   handwritten, registered in `ALL_MIGRATIONS` and `DataModule`; migration writes are in one
   transaction and destructive fallback is prohibited.
4. T-002's canonical registry is the reviewable authority: each entry has a stable registry key,
   new stable identity, display metadata, canonical role map, coverage tags, and an explicit map
   from every existing built-in name/syncId. Canonical catalog identity is therefore not a mutable
   display-name lookup. Reconciliation resolves that legacy identity first and preserves local row
   `id`/history, then inserts only unmatched registry entries; it replaces every built-in map,
   changes neither custom exercises nor `gym_exercises`, and is idempotent. Built-in classification
   and Sheets authority derive from the local registry, never remote `isCustom`.
5. UI is UDF: immutable screen UiState/state holders down; sealed user events up; transient
   messages through existing buffered one-shot event flow. `SavedStateHandle` holds selected muscle
   (and route identity) only; Room owns map/review data. `BodyMapFlip` is parameter-driven and has
   a single selection source; view auto-switches only for known geometry metadata.
6. UI uses hardcoded Kotlin text, `MaterialTheme.colorScheme`, `GymMotion`, and `GymHaptics.tap()`
   once after a settled changed selector value. Cyclic presentation exposes finite Previous/Next/
   Open list semantics, 48dp targets, text role/value, keyboard/Switch Access and 200% font path.
7. No new Hilt scope: database/DAOs/repositories stay singleton/application scoped; VM owns
   `viewModelScope` cancellation. Heavy catalog projection/analytics `Flow` uses injected
   `@ComputeDispatcher` and `flowOn`; no `GlobalScope` or raw dispatcher.
8. The once-only v13 “history recalculated under the new muscle model” notice is backed by a Room
   migration marker: `MIGRATION_12_13` inserts it, while a fresh v13 database starts without it.
   Only completed historical work makes the marker visible; the UI acknowledges it after delivery,
   so recreation before acknowledgement redelivers it. It never changes workout rows or active-workout
   recording.

## Tasks

| ID | Owner / dependencies | Exact files | Action | Automated verification / done condition | AC |
|---|---|---|---|---|---|
| T-001 | Implementation writer; none | `data/db/entity/Muscle.kt`, `ExerciseMuscleEntity.kt`, `ExerciseEntity.kt`, `MuscleLoadUpgradeNoticeEntity.kt`; `data/db/dao/ExerciseMuscleDao.kt`, `MuscleLoadUpgradeNoticeDao.kt`; `data/db/GymDatabase.kt`; `di/DataModule.kt`; `app/schemas/.../13.json`; `test/data/db/Migration12To13Test.kt`, `Migration12To13RoomTest.kt`, `Migration1To12Test.kt`, `ExerciseMuscleDaoTest.kt` | Define 25 IDs/roles and review flag; implement/register v12→v13 DDL+DML conversion and upgrade marker, preserving rows/IDs. Delete only legacy zero rows; convert legacy `1..24` to explicit `0`; duplicate converted custom CHEST; update transactional DAO contract/schema. | Targeted direct-DML and `MigrationTestHelper` v12→v13 Room-open tests plus the production full path prove target-schema validation, legacy-zero absence, legacy stabilizer explicit zero, CHEST/review, marker semantics and protected rows. | 004,018–020 |
| T-002 | Implementation writer; T-001 | `data/db/CanonicalExerciseRegistry.kt`, `SeedExercises.kt`, `SeedExerciseMuscles.kt`, `MuscleDefaults.kt`; `test/data/db/CanonicalExerciseRegistryTest.kt`, `SeedExerciseMusclesTest.kt`, `MuscleDefaultsTest.kt` | Create/freeze reviewable 250–350 registry: stable key/new identity, display metadata, canonical map, coverage tags, and explicit old built-in name/syncId mapping; encode scope coverage and pseudo-duplicate guards. | Targeted registry/seed tests prove count range, complete legacy mapping, map parity, stable identities, no pseudo-duplicates and every matrix family. | 015,016,017 |
| T-003 | Implementation writer; T-001,T-002 | `data/db/ExerciseMuscleSeeder.kt`, `GymDatabaseCallback.kt`, `data/db/dao/ExerciseDao.kt`; `test/data/db/SeedExerciseMusclesTest.kt`, `ExerciseDaoTest.kt`, `data/ExerciseCatalogRepositoryImplTest.kt` | Atomically reconcile only registry-known built-ins: resolve legacy identity before insert, preserve local `id`/history, replace their maps and insert unmatched; retire generic custom fallback in `seedMissingExerciseMuscles` so no-row custom stays absent. Do not change gym links. | Targeted tests prove old-name upgrade, rerun idempotence, cloud rollback resistance, retained links/no gym additions; upgraded all-zero custom remains absent, stabilizer-only custom remains explicit zero, and unmapped imported custom stays absent after onOpen. | 004,015–017,022 |
| T-004 | Implementation writer; T-001,T-003 | `domain/ExerciseSheetRows.kt`, `data/google/ConfigurationSheetsRepository.kt`, `data/google/WorkoutImportRepository.kt`, `worker/ConfigurationUploadScheduler.kt`; `test/domain/ExerciseSheetRowsTest.kt`, `test/data/WorkoutImportRepositoryTest.kt`, `test/data/ConfigurationSheetsRepositoryTest.kt`, `test/worker/UploadSchedulerTest.kt` | Accept and safely extend legacy A:I, export canonical A:J rows with `model_version=2` and explicit IDs/100/50/0 for custom only; accept legacy CHEST/0..100 through one converter in the actual import path; preserve zero-only exercises as empty maps, round-trip canonical stabilizers, skip unknown/bad future maps nonfatally, and enforce local-registry built-in authority/custom-only LWW. | Listed parser/import/scheduler tests prove legacy header upgrade, zero-only continuity, explicit zero round-trip, valid peers surviving invalid maps, and remote `isCustom` unable to overwrite a registry built-in. | 004,017,018,021 |
| T-005 | Implementation writer; T-001 | `domain/analysis/AnalyticsEngine.kt`, `MuscleLandmarks.kt`, `domain/ExerciseCatalogProjector.kt`, `domain/EnumDisplay.kt`; `test/domain/analysis/AnalyticsEngineTest.kt`, `MuscleLandmarksTest.kt`, `test/domain/ExerciseCatalogProjectionTest.kt` | Replace threshold semantics with role semantics; implement per-set chest maximum before aggregate/balance, including legacy-split maps; primary/secondary search excludes stabilizer-only; keep costly calculation on `@ComputeDispatcher`. | Targeted analytics/projector tests cover strength, timed, cardio, multi-primary, stabilizer, upper/lower chest max, cancellation-compatible flow. | 001–005,020 |
| T-006 | Implementation writer; T-001,T-005,T-007,T-008 | `ui/library/ExerciseEditorSheet.kt`, `ExerciseLibraryViewModel.kt`, `ExerciseLibraryScreen.kt`, `AiExerciseCreationSheet.kt`; `ui/exercise/ExerciseDetailViewModel.kt`, `ExerciseDetailScreen.kt`; `domain/GymRepository.kt`, `data/GymRepositoryImpl.kt`; `test/ui/ExerciseLibraryViewModelTest.kt`, `ExerciseDetailViewModelTest.kt`, `ui/library/ExerciseLibraryScreenTest.kt`, `test/data/GymRepositoryImplTest.kt` | Replace intensity slider with four-state role controls and selected text list; preserve explicit stabilizer; enforce primary on save. Implement personalization as one transaction creating a new custom id/syncId with copied map: original/history/gym/routine/set links untouched and clone gets no gym link absent an explicit existing user choice. Show/clear legacy review only after successful edit; model loading/empty/error/content and one-shot validation events. | Targeted VM/screen/repository tests use handwritten fakes/live collection; prove failed save/no write, multi-primary/stabilizer, clone transaction/identity/no gym auto-assignment/review clear, semantic labels/actions, fontScale 2, back and SavedStateHandle restoration. | 001,004,010–012,019,023 |
| T-007 | Implementation writer; T-001 | `data/ai/ExerciseAiGenerator.kt`, `data/ai/AiApi.kt`; `test/data/ai/AiApiExerciseAiGeneratorTest.kt` | Freeze prompt/JSON parser to 25 IDs + canonical roles, including explicit stabilizer zero, unique rows and ≥1 primary; return a validated draft only (no persistence). | Targeted generator tests prove explicit stabilizer preservation and bad/network/duplicate/unknown no-partial-write behavior. | 004,013,014 |
| T-008 | Implementation writer; T-001 | `ui/analysis/body/BodyMap.kt`, `BodyMuscleMapping.kt`, `BodyFrontPaths.kt`, `BodyBackPaths.kt`, `MuscleSelector.kt`; `ui/analysis/AnalysisScreen.kt`, `AnalysisViewModel.kt`; `test/ui/BodyMapHitTest.kt`, `AnalysisViewModelTest.kt`, `ui/AccessibilityFoundationTest.kt`, `ui/AdaptiveNavigationTest.kt` | Extend geometry/metadata to 25; add reusable cyclic centered selector with tap neighbors/full list, finite semantic actions, auto-side and off-geometry choices. Persist only selected muscle in `SavedStateHandle`; issue one settled-change haptic. | Targeted semantics/hit/VM/adaptive tests cover all 25, actions, side choice, fontScale 2, targets, state restoration; no screenshot inspection. | 006–009,023 |
| T-009 | Implementation writer; T-005,T-008 | `ui/analysis/MuscleLoadCards.kt`, `AnalysisScreen.kt`; `test/ui/AnalysisViewModelTest.kt`, `AnalysisRenderTest.kt` | Render role/effective-set explanation, selected map/list text alternative, correct empty/error/content states and chest-safe balances using chart contracts. | Run `./gradlew :app:testDebugUnitTest --tests '*AnalysisViewModelTest' --tests '*AnalysisRenderTest'`; do not open generated render snapshots without explicit user permission. | 001–005,023 |
| T-010 | Implementation writer; T-001,T-005 | `data/db/entity/MuscleLoadUpgradeNoticeEntity.kt`, `data/db/dao/MuscleLoadUpgradeNoticeDao.kt`, `data/settings/MuscleLoadUpgradeNotice.kt`, `ui/analysis/AnalysisViewModel.kt`, `AnalysisScreen.kt`; `test/data/settings/MuscleLoadUpgradeNoticeTest.kt`, `test/ui/AnalysisViewModelTest.kt` | Use the migration-created Room marker for a once-only nonblocking v13 recalculation notice; require completed history, acknowledge only after delivery, survive recreation, and never touch workout data/recording. | Room-backed notice/VM tests cover fresh installs, active-only data, completed-history upgrades, redelivery before acknowledgement and once-only acknowledgement. | 020,024 |
| T-011 | Implementation writer; T-001–T-010 | `app/build.gradle.kts`; `test/ui/active/ActiveWorkoutScreenTest.kt`, `test/data/WorkoutDaoTest.kt` | Confirm active recording has no new event/action and all migration integrity assumptions hold; apply sole version bump. | Targeted active/DAO tests pass; source shows exactly `versionCode 16`, `versionName "1.3.8"`. | 020,024 |
| T-012 | Root; T-001–T-011 | `vibe/muscle-load-system-plan-track.md` | Run final gates once after stable diff; record output and any deviation/finding. | `./gradlew :app:testDebugUnitTest` then `./gradlew :app:assembleDebug`, sequentially, both green. | 001–024 |

## File ownership and execution waves

One implementation writer owns all T-001…T-012 production/test files; no parallel writer is
permitted because Room, catalog identity, Sheets, DI, navigation/state and shared BodyMap contracts
overlap. The writer must preserve concurrent unrelated edits. `GymDatabase`/entities/DAOs/migration/
schema/migration tests are exclusively T-001; `DataModule`, Hilt registration, navigation-facing
state and `app/build.gradle.kts` remain that same writer's shared choke points.

| Boundary | Exclusive owner | Files/contract |
|---|---|---|
| Persistence + catalog | Implementation writer | T-001–T-003: entities, DAOs, Room v13/schema, registry/seed/reconciler |
| Sync + domain math | Implementation writer | T-004–T-005,T-007: Sheets/import/worker, analytics/projector, AI parser |
| Shared UI choke points | Implementation writer | T-006,T-008–T-011: BodyMap/selector, VMs/routes/screens, notice, version |

| Wave | Tasks | Gate |
|---|---|---|
| 1 | T-001 | v12→13 and v1→13 migration/schema gate |
| 2 | T-002, T-003, T-004, T-005 | seed/reconcile/import/analytics targeted unit gates |
| 3 | T-007, T-008, then T-006, T-009 (ordered, one writer) | VM/AI/semantics/adaptive/render targeted gates |
| 4 | T-010, T-011, T-012 | upgrade notice/active-workout regression, then final project gates once |

## Quality gates

- Always: JUnit4, private handwritten fakes, live collection for `stateIn(WhileSubscribed)`, no
  mocks/logs/dependencies/destructive migration; UI strings Kotlin; colors/motion/haptics tokens.
- Room: manual v12→13, registry, exported `app/schemas/13.json`, incremental and v1 full-path
  Room tests; assert legacy zero removal vs canonical explicit-stabilizer preservation, plus every
  protected workout/routine/gym/set row and IDs/sync IDs unchanged.
- Import/sync: A:I legacy and canonical parser/export, LWW custom-only and invalid-map continuation.
- UI/adaptive/accessibility: semantics tree (not screenshots), 48dp, keyboard/Switch Access,
  200% font, compact/medium/expanded, loading/empty/error/content and back/restoration.
- Analytics chart conditional: run `AnalysisRenderTest`; do **not** inspect its snapshots without
  explicit owner permission. No new WorkManager/system/permission/release gate is relevant.
- Final project gates, once and in order: `./gradlew :app:testDebugUnitTest`; then
  `./gradlew :app:assembleDebug`.

## Risks, unresolved questions, rollback/data preservation

The canonical 250–350 matrix is the main content risk: tests enforce coverage/count/identity but
not biomedical truth; conservative secondary roles are the fallback. Legacy CHEST necessarily
approximates two regions, controlled by per-set max and durable review. Old clients can emit legacy
maps; converter remains input-only. A corrupted future map is skipped, never crashes import.

No unresolved blocking product question. Before release, retain a v12 database fixture and exported
schemas; rollback to an older binary is unsafe after v13 without restoring a backup, so do not
support downgrade/destructive fallback. Migration and reconciliation must never touch completed-set
rows, workout/routine/gym membership, or existing exercise `id`/`syncId`.

## Gate P self-check

Pass: every AC-001…AC-024 maps above to a task and an automated/manual verification; explicit
canonical stabilizer is distinct from migrated legacy zero in migration, DAO/editor/AI/Sheets tests;
the registry/legacy-identity/seed limits and clone transaction are frozen before implementation;
one writer eliminates ownership overlap; waves and dependencies are explicit; Room, Sheets/sync,
Room migration notice, dispatcher/cancellation, Hilt, UI restoration/accessibility, analytics,
background-work relevance, migrations and final/conditional gates are covered. No plan reviewer is
required beyond this strict-path self-check unless implementation changes a frozen contract.
