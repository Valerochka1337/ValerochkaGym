# Локальная замена недоступного упражнения — implementation plan

Feature: `exercise-recommendations` · stage 16 / REC-01 / #11 · fast path.

## Goal, scope, non-goals, assumptions

Give an editable routine a local, explainable replacement choice for each exercise that is unavailable in every selected gym.  The choice changes only the unsaved editor row after a confirmation that says its set values and rest will be cleared.

Scope: the routine editor, a read-only replacement-catalog boundary, a pure deterministic ranker, Hilt binding, focused tests, and the single required app-version increment.  D-006 fixes the MVP to local unavailable-exercise replacement.

Out of scope: AI, backend/network calls, active-workout changes, new Room entities/DAOs/schema/migration, new permissions, WorkManager/service work, history/load transfer, or a claim of medical equivalence.  The existing `GymRepository.saveRoutineConfiguration` transaction remains the authoritative save-time availability check.

Assumptions: a verified map is `needsMuscleMapReview == false` and has at least one 100-role primary muscle; legacy unknown equipment is unsafe; a blank selected-gym set is deliberately not a recommendation context.  Existing unsaved editor drafts are memory-only; configuration changes retain the ViewModel, while process recreation returns to the normal saved-routine/new-routine state and closes transient replacement UI.

## Acceptance criteria

| ID | Criterion |
|---|---|
| AC-001 | A guest/offline editable routine gets a local replacement list without AI or network. |
| AC-002 | Candidates must be available in every selected gym, non-archived, the same type, absent from the draft, have known equipment, and have verified muscle maps; each no-result condition is explained. |
| AC-003 | At most five candidates are deterministically ranked by weighted Jaccard, shared primaries, then `syncId`; zero-primary overlap is excluded. |
| AC-004 | Confirmed replacement changes only its draft row, preserves row position and set count, clears every set parameter and rest; Cancel/Back write nothing. |
| AC-005 | A changed gym set, catalog, source row, draft membership, or read-only state invalidates an open choice; save still uses the existing transactional availability check. |
| AC-006 | Ranking runs through `flowOn(@ComputeDispatcher)`; the UI uses project tokens, semantic actions, 48dp controls, large-font-safe content, and modal Back dismissal. |

## Current and target flow

**Current:** Room inventory/exercise/muscle tables → `GymRepository.observeAvailableExercises` → `RoutineEditorViewModel.conflictingExercises` → editor warning → `saveRoutineConfiguration` transaction.

**Target:** Room remains SSOT. `ExerciseReplacementCatalogRepository` combines the full exercise snapshot, all muscle roles and equipment states with `GymRepository.observeAvailableExercises(selectedGymIds)`; it exposes immutable local catalog snapshots only. `RoutineEditorViewModel` owns the in-memory draft and immutable replacement modal state. On the user event `openReplacement(editorId)`, `flatMapLatest` joins the request with current catalog/draft, calls the pure ranker upstream of `flowOn(@ComputeDispatcher)`, and emits loading, explanation, or five candidates. The screen raises open/select/confirm/dismiss events and never writes data. `confirmReplacement` revalidates the request fingerprint against the current editable draft, gyms, candidate availability, type and ranker output before copying that one `EditorExercise`; it keeps `editorId`, list index and `plannedSets.size`, replaces every set with `PlannedSet()`, and makes `restSeconds = null`. Only the existing Save event persists the draft transactionally.

`flatMapLatest` owns cancellation of a superseded request/catalog calculation; `viewModelScope` owns collection. The repository exposes no mutable data and performs no write. No background work, permission, navigation destination, or migration is introduced.

## Frozen contracts and decisions

- `ExerciseReplacementCatalogRepository.observe(selectedGymIds)` is the sole feature read boundary. It is Hilt-bound as `@Singleton`; its inputs are Room-backed repository/DAO Flows, not UI cache or network. It returns all exercises plus `availableExerciseIds`, roles, and requirement states so an unavailable source can still be validated.
- `ExerciseReplacementRanker.rank(source, draftExerciseIds, catalog)` is pure. It rejects the source/candidates unless map review is clear, their role map has a primary, requirements are not `UnknownLegacy`, the candidate is in `availableExerciseIds`, non-archived, same `ExerciseType`, and outside `draftExerciseIds`. Score only positive role values: `sum(min)/sum(max)` over the muscle union; require one shared 100-role primary; compare fractions by integer cross multiplication, then descending shared-primary count, then ascending stable `syncId`; return the first five. UI exposes shared-primary names and “доступно во всех выбранных залах”, never a percentage.
- `ReplacementRequest` carries the source `editorId`, source exercise id, ordered draft exercise ids, selected gym ids, and catalog revision. Applying requires exact current equality plus `isEditable`; any mismatch closes the picker with “Список устарел, обновите подбор”. This is a guard, not a persistent operation journal.
- The modal has explicit `Loading`, `NeedsGym`, `MissingVerifiedSourceMap`, `Empty`, `Candidates`, `Confirm`, and `Stale` states. The confirm text explicitly states “сохранится N подходов; вес, повторы/время и отдых очистятся”. Cancel, scrim and Back dispatch dismiss only.
- The modal is screen state, not a route. `BackHandler` dismisses it before the editor’s normal back action. It is not saved in `SavedStateHandle`; re-opening recalculates from Room and the current draft. No replacement is visible or actionable for `origin == STANDARD`, loading, or saving.
- UI uses `GymCard`, `MaterialTheme.colorScheme.*`, `GymMotion` only if an existing animation is retained, and `GymHaptics` only for an intentional existing semantic action. Candidate rows have role/state descriptions and 48dp action targets; a bounded, scrollable M3 modal remains usable at `fontScale = 2.0` and compact/medium/expanded widths.

## Tasks

| ID | Owner | Exact files | Depends on | Action | Automated verification | Done condition | AC |
|---|---|---|---|---|---|---|---|
| T-001 | Android writer | `app/src/main/java/com/valerochka1337/valerochkagym/domain/ExerciseReplacementCatalogRepository.kt`; `app/src/main/java/com/valerochka1337/valerochkagym/domain/ExerciseReplacementRanker.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/domain/ExerciseReplacementRankerTest.kt` | — | Define immutable catalog/request/result models and the pure ranker; cover positive-role Jaccard, zero-overlap rejection, every filter, top-5, and both tie breaks. | `./gradlew :app:testDebugUnitTest --tests "*ExerciseReplacementRankerTest"` | Fixtures prove the frozen ordering and no invalid candidate escapes. | AC-002, AC-003 |
| T-002 | Android writer | `app/src/main/java/com/valerochka1337/valerochkagym/data/ExerciseReplacementCatalogRepositoryImpl.kt`; `app/src/main/java/com/valerochka1337/valerochkagym/di/DomainModule.kt` | T-001 | Combine existing local Flows into the catalog contract, preserve cancellation, and bind the implementation `@Singleton`. Do not alter Room, network, workers, or catalog-selection behavior. | `./gradlew :app:compileDebugKotlin` | Hilt supplies a local-only repository whose snapshot can distinguish source catalog rows from currently available candidates. | AC-001, AC-002, AC-005 |
| T-003 | Android writer | `app/src/main/java/com/valerochka1337/valerochkagym/ui/routine/RoutineEditorViewModel.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/RoutineEditorViewModelTest.kt` | T-001, T-002 | Inject the catalog boundary and `@ComputeDispatcher`; add immutable modal state/events, `flatMapLatest` ranking with `flowOn`, request fingerprint guard, and confirm-copy/reset operation. Extend private handwritten fakes and collect live state in tests. Test offline list, invalid filters/reasons, reset/count/position, cancel, stale catalog/gym/row/read-only rejection, and the untouched save conflict path. | `./gradlew :app:testDebugUnitTest --tests "*RoutineEditorViewModelTest"` | All state transitions are observable; stale or cancelled selections cannot mutate or persist the draft. | AC-001–AC-005, AC-006 |
| T-004 | Android writer | `app/src/main/java/com/valerochka1337/valerochkagym/ui/routine/RoutineEditorScreen.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/routine/RoutineEditorScreenTest.kt` | T-003 | Add the conflict-row entry point and bounded M3 replacement/confirmation modal, wire all events, explanatory states, dismiss/Back, semantics, and large-font test content. Keep stable `editorId` list keys and existing normal editor navigation. | `./gradlew :app:testDebugUnitTest --tests "*RoutineEditorScreenTest"` | Compose checks prove candidate/empty/cancel rendering, explicit reset warning, Back dismissal, semantic labels, and `fontScale = 2.0` content access. | AC-002, AC-004, AC-006 |
| T-005 | Android writer | `app/build.gradle.kts`; `vibe/exercise-recommendations-plan-track.md` | T-001–T-004 | Increase `versionCode` once and patch `versionName` once; run final sequential gates after the stable code diff and record exact outcomes/deviations. | `./gradlew :app:testDebugUnitTest`; then `./gradlew :app:assembleDebug` | Version is exactly one increment for this feature and final gates pass. | AC-001–AC-006 |

## Ownership and execution waves

| Files/boundary | Sole owner |
|---|---|
| New domain catalog contract and ranker, domain test | Android writer |
| Repository implementation and `DomainModule` Hilt choke point | Android writer |
| Routine editor ViewModel and its test | Android writer |
| Routine editor screen and Compose/Robolectric test | Android writer |
| Version and tracker results | Android writer |

One writer owns every mutable production/test/build file; no implementation runs in parallel. Wave 1: T-001. Wave 2: T-002. Wave 3: T-003 and T-004 sequentially (shared editor contract). Wave 4: T-005 and final gates. This is fast-path work: it adds no durable schema/sync/public API/permission/system-component contract. Reclassify to strict only if implementation must change one of those contracts.

## Quality gates

Relevant conditional gates: pure unit tests for ranking; ViewModel tests with `MainDispatcherRule`, `runTest(mainDispatcherRule.testDispatcher.scheduler)`, a live collector for any `WhileSubscribed` state, and private handwritten fakes; Robolectric/Compose tests only for modal semantics and large fonts; `flowOn(@ComputeDispatcher)` for the ranking transform; sequential final `:app:testDebugUnitTest` then `:app:assembleDebug`. No Room migration/schema, WorkManager/service, manifest/permission, dependency, release/R8, chart-render, or adaptive-navigation gate is applicable.

## Risks, open questions, rollback/data preservation

The rank is a transparent muscle-role heuristic, not a claim of equivalence; copy must keep that modest framing. Catalog changes while the modal is visible are expected and handled by invalidation rather than optimistic replacement. A malformed legacy map/requirements produces an explanation and leaves manual selection available. The source or candidate may disappear between render and confirmation, so the ViewModel rechecks before draft mutation and the existing transaction rechecks at Save.

No persisted rows are modified until existing Save succeeds; Cancel/Back and failed/stale confirmation preserve the in-memory draft. Rollback is removal of this UI/read-only ranking flow; no migration or data restoration is required. No unresolved product blocker remains.

## Gate P self-check

Every AC maps to T-001–T-005 and a focused or final automated command. All implementation ownership is single-writer and non-overlapping. The algorithm, read boundary, stale guard, reset semantics, lifecycle/cancellation, Hilt scope, navigation/restoration, and exclusions are frozen before implementation. Only relevant quality gates are included.
