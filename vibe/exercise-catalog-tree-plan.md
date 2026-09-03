# План: фасетный каталог упражнений

Статус: уточнение owner feedback. Slug: `exercise-catalog-tree`. Основание: согласованный [Feature Brief](exercise-catalog-tree-product.md), AC-001…AC-019.

## Goal, scope, non-goals, assumptions

Цель — дать существующей библиотеке офлайн-проекцию базовых упражнений: единый плоский список с режимами порядка «Недавние»/«Частые», поиском и фасетами крупной группы, типа и происхождения; это не меняет ID, историю или picker-контракт.

В scope: поиск по имени/русским label группы, мышц с вкладом `>=25` в той же сохранённой группе и типа; фасеты group/type/origin из отдельной filter sheet, alphabet/recent/frequent из отдельной sort sheet; loading/empty/content, TalkBack, `fontScale=2.0`, compact/medium/expanded. Ограниченный залами поток применяется до любой проекции. Не входят: hierarchy конкретных мышц, изменение Room schema/entity/migration, Sheets/sync, сеть/permissions/workers/services, dependencies, equipment/movement patterns/synonyms, variants/variant search, новые routes и telemetry. Единственное Room-касание — узкая read-only DAO-проекция истории для уже существующих строк.

Допущения: recent = максимальный `WorkoutEntity.finishedAt` из новой узкой finished-only истории `(exerciseId, workoutId, finishedAt)`; `completedAt` подхода для AC-007 не применяется. Frequent = число разных `workoutId`, не подходов. UI допускает один выбор на фасет (AND между фасетами); query/facets/sort восстанавливаются из `SavedStateHandle` и исчезают с pop route.

## Acceptance criteria and traceability

| AC | Result | Task / automated evidence |
|---|---|---|
| AC-001 | recent/frequent reorder the single flat overview | T-001,T-003,T-006; projection/VM tests |
| AC-002 | no history leaves the single overview available | T-001,T-006; projection/VM tests |
| AC-003 | stored group filter keeps all flat rows reachable via All | T-001,T-003,T-006; projection/VM tests |
| AC-004 | one base record/history through flat search/filter/sort | T-001,T-006; projection/VM tests |
| AC-005 | case-insensitive name/group/muscle/type search | T-001,T-003; projection/VM tests |
| AC-006 | AND facets and no duplicates | T-001,T-003; projection/VM tests |
| AC-007 | recent excludes active and orders by workout `finishedAt` | T-001,T-002,T-003; DAO/projection/repository/VM tests |
| AC-008 | frequent counts distinct completed workouts | T-001,T-002,T-003; projection/repository/VM tests |
| AC-009 | gym intersection precedes all surfaces | T-002,T-003; repository/VM plus `*GymDaoTest`, `*GymRepositoryImplTest` |
| AC-010 | saved custom map updates live | T-002,T-003; VM test |
| AC-011 | unmapped entry remains list/search/group/type only | T-001,T-003; projection/VM tests |
| AC-012 | explanatory empty + single reset | T-003,T-004; VM test and owner manual review |
| AC-013 | picker back/cancel has no write | T-003,T-004; `*ActiveWorkoutViewModelTest`, `*RoutineEditorViewModelTest`, owner manual review |
| AC-014 | local browse/filter/select works offline | T-002,T-003; repository/VM test |
| AC-015 | semantic state/actions, 48dp and 2x font | T-004; owner-waived screen automation, manual review |
| AC-016 | IDs/history/Room/Sheets unchanged | T-001,T-002,T-005; regressions/final gates |
| AC-017 | flat overview visibly reorders for every sort | T-006; projector/VM tests and owner visual review |
| AC-018 | separate search-bar sheets for type/origin/group counts, no hidden muscle | T-006; projector/VM tests and owner visual review |
| AC-019 | immediate top scroll and fast list placement token | T-007; code review/manual walkthrough |

## Current and target flow

```
Current: ExerciseDao | GymRepository.available → VM name/group filter → flat LazyColumn
Target: GymRepository.available(selectedGymIds) [SSOT/intersection]
      + ExerciseMuscleDao.observeAll + WorkoutDao.observeFinishedExerciseHistory
      → read-only ExerciseCatalogRepository → pure ExerciseCatalogProjector
      → immutable UI StateFlow(WhileSubscribed(5000)) → Compose events
```

Room remains SSOT. Repository joins existing reactive reads; projector owns search/filter/group/rank/dedupe; ViewModel owns saved presentation state and `viewModelScope` cancellation; Compose owns only scroll/sheet state. Browsing/reset/back never writes.

## Architectural decisions and frozen contracts

- Add `WorkoutDao.observeFinishedExerciseHistory(): Flow<List<ExerciseWorkoutHistoryRow>>`: a read-only `SELECT DISTINCT we.exerciseId, w.id AS workoutId, w.finishedAt` joined through a completed set, constrained to `w.finishedAt IS NOT NULL`. It is the only new DAO API and requires neither entity/schema/version/migration nor Sheets change. `ExerciseCatalogRepository.observeCatalog(gymIds)` combines it with `GymRepository.observeAvailableExercises(gymIds)`, `ExerciseMuscleDao.observeAll`, and gym labels; bind singleton implementation in `DomainModule`.
- Pure `ExerciseCatalogProjector` receives available `ExerciseEntity`, muscle map and `ExerciseWorkoutHistoryRow`; all output retains original `ExerciseEntity.id`. The only anatomy facet is stored `ExerciseEntity.muscleGroup`; muscle-map labels enter search only at contribution `>=25` in that same stored group. Unmapped, `CARDIO`, `FULL_BODY`, and mismatched-map entries remain ordinary flat rows.
- Immutable `ExerciseLibraryUiState`: nullable exercises (loading), query, filters, sort, gym labels, derived count/empty reason. Explicit events are query/facet/sort changes, reset and existing editor events. Save only query/filter/sort presentation keys to route `SavedStateHandle`, never Room/DataStore.
- Existing `GymRoutes.LIBRARY` args, direct screen Back pop, `SELECTED_EXERCISE_ID`, picker callback and `create/updateExercise...AddToWorkout` atomic writes remain the only selection writes.
- Projection is upstream of `stateIn`, runs with `flowOn(@ComputeDispatcher)`, is cancelled by `viewModelScope`; no `GlobalScope`, network, worker/service, permission, logging or dependency.
- UI uses existing `GlowBackground`, `GymCard`, `GymFilterChip`, `FadeInContent`, `GymMotion`, `gymHaptics`, Material colors/shapes, Kotlin strings and stable Lazy keys. It supplies direct Back, selected filter state, reset labels and >=48dp targets. A project-owned `GymWindowWidthClass` (`Compact <600dp`, `Medium 600..<840dp`, `Expanded >=840dp`) is computed once from the shell's available width in `MainScaffold` and passed through `GymNavGraph`: compact uses a 16dp single column; medium/expanded use 24dp gutters and one centered max-840dp column (no list-detail because choosing remains a pop/detail action). This adds no dependency. Facets remain horizontally scrollable rather than clipped at 2x font.

## Tasks

### T-001 — Pure catalog projection

| Field | Detail |
|---|---|
| Owner | Implementation writer |
| Dependencies | — |
| Exact files | `app/src/main/java/com/valerochka1337/valerochkagym/data/db/relation/ExerciseWorkoutHistoryRow.kt` (new); `app/src/main/java/com/valerochka1337/valerochkagym/domain/ExerciseCatalogProjector.kt` (new); `app/src/test/java/com/valerochka1337/valerochkagym/domain/ExerciseCatalogProjectionTest.kt` (new) |
| Actions | Define the shared pure history-input row `(exerciseId, workoutId, finishedAt)` before any consumer, then immutable snapshot/projection/filter/sort models and pure mapping. Cover flat ordering, valid muscle search labels/case, AND/dedupe, `finishedAt` ranking input, stored-group filtering, and unmapped/CARDIO/FULL_BODY/mismatched custom rows. |
| Automated verification | `./gradlew :app:testDebugUnitTest --tests '*ExerciseCatalogProjectionTest'` |
| Observable done condition | Output preserves base IDs; map labels below `25` or from another stored group do not enter search; CARDIO/FULL_BODY and unmapped rows remain available in the flat list; deterministic tests cover AC-001…008 and AC-011. |
| AC | AC-001…AC-008, AC-011, AC-016 |

### T-002 — Read-only catalog source and Hilt boundary

| Field | Detail |
|---|---|
| Owner | Implementation writer |
| Dependencies | T-001 frozen contract |
| Exact files | `app/src/main/java/com/valerochka1337/valerochkagym/data/db/dao/WorkoutDao.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/data/WorkoutDaoTest.kt`; `app/src/main/java/com/valerochka1337/valerochkagym/domain/ExerciseCatalogRepository.kt` (new); `app/src/main/java/com/valerochka1337/valerochkagym/data/ExerciseCatalogRepositoryImpl.kt` (new); `app/src/main/java/com/valerochka1337/valerochkagym/di/DomainModule.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/data/ExerciseCatalogRepositoryImplTest.kt` (new) |
| Actions | Add only the finished-workout history query above, test its distinct exercise/workout rows, `finishedAt` values and active/incomplete exclusion. Repository combines it with local catalog/map/labels; selected-gym availability is applied before snapshot projection. Repository test owns initial/loading, gym intersection, live catalog/map/history updates, selected-gym labels and offline/no-network behavior; singleton Hilt binding. No entity/schema/version/migration/Sheets edit. |
| Automated verification | `./gradlew :app:testDebugUnitTest --tests '*WorkoutDaoTest' --tests '*ExerciseCatalogRepositoryImplTest' --tests '*GymDaoTest' --tests '*GymRepositoryImplTest'`; `./gradlew :app:compileDebugKotlin` |
| Observable done condition | DAO emits exactly one finished-history row per base exercise/workout with `finishedAt`; map/catalog/history/labels changes re-emit snapshot; intersection is applied first; Hilt compiles and no schema artifact changes. |
| AC | AC-007…AC-011, AC-014, AC-016 |

### T-003 — ViewModel state, restoration and cancellation

| Field | Detail |
|---|---|
| Owner | Implementation writer |
| Dependencies | T-001,T-002 |
| Exact files | `app/src/main/java/com/valerochka1337/valerochkagym/ui/library/ExerciseLibraryViewModel.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/ExerciseLibraryViewModelTest.kt` |
| Actions | Inject repository and `@ComputeDispatcher`; project upstream of `stateIn(WhileSubscribed(5000))` using DAO `finishedAt` history. Store only query, group/type/origin facets and sort; reset filters independently from query/sort. Preserve direct-test NoOp editor behavior, AI cancellation and atomic picker save. Use private handwritten fakes plus live collector. |
| Automated verification | `./gradlew :app:testDebugUnitTest --tests '*ExerciseLibraryViewModelTest' --tests '*ActiveWorkoutViewModelTest' --tests '*RoutineEditorViewModelTest'` |
| Observable done condition | recreation restores valid query/filter/sort presentation; catalog/map/history updates live; `resetFilters` preserves query/sort; direct Back delegates pop; cancel does not emit selection. |
| AC | AC-001…AC-014, AC-016 |

### T-004 — Facet UI and accessibility

| Field | Detail |
|---|---|
| Owner | Implementation writer |
| Dependencies | T-003 |
| Exact files | `app/src/main/java/com/valerochka1337/valerochkagym/ui/navigation/GymWindowWidthClass.kt` (new); `app/src/main/java/com/valerochka1337/valerochkagym/ui/navigation/MainScaffold.kt`; `app/src/main/java/com/valerochka1337/valerochkagym/ui/navigation/GymNavGraph.kt`; `app/src/main/java/com/valerochka1337/valerochkagym/ui/library/ExerciseLibraryScreen.kt` |
| Actions | Render one flat filtered/sorted result list with loading and resettable empty states; keep filter and sort sheets transient. Compute the explicit project width class once in `MainScaffold` from its available width and thread it through graph to screen: compact 16dp; medium/expanded centered max-840dp with 24dp gutters; scroll facet controls. Owner explicitly waives screen automation; manual review covers semantics, picker/cancel, font scale and width classes without screenshots. |
| Automated verification | targeted projector + ViewModel tests and `:app:compileDebugKotlin`; no screen test |
| Observable done condition | Flat list has non-color state/action labels and >=48dp targets; owner performs manual TalkBack, 2x-font, width, direct Back and picker-cancel walkthrough. |
| AC | AC-001…AC-006, AC-009…AC-015 |

### T-005 — Version and stable feature gates

| Field | Detail |
|---|---|
| Owner | Implementation writer |
| Dependencies | T-001…T-004 |
| Exact files | `app/build.gradle.kts`; `vibe/exercise-catalog-tree-plan-track.md` |
| Actions | One version increment `11→12`, `1.3.3→1.3.4` unless integration target advanced; record actual results. No Room/schema/release gate: those scopes are unchanged. |
| Automated verification | `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug` |
| Observable done condition | Pair passes once on stable diff; version is above target and tracker proves every AC. |
| AC | AC-001…AC-016 |

### T-006 — Owner refinement: flat overview and counted facets

| Field | Detail |
|---|---|
| Owner | Implementation writer |
| Exact files | `ExerciseCatalogProjector.kt`, `ExerciseLibraryViewModel.kt`, `ExerciseLibraryScreen.kt`, `GymFilterChip.kt`, projector/VM tests and feature docs |
| Actions | Replace overview prefixes with one flat result list; add type families and contextual pure filter counts. Put distinct 48dp filter/sort actions in the search field and open separate Material 3 sheets; remove all anatomy hierarchy, quick sections and their saved state while preserving direct picker route behavior. Preserve the current version because this refines the unmerged feature. |
| Verification | targeted projector + ViewModel tests; `:app:compileDebugKotlin` |
| AC | AC-017, AC-018 |

### T-007 — Owner refinement: top reset and fast list rearrangement

| Field | Detail |
|---|---|
| Owner | Implementation writer |
| Exact files | `ExerciseLibraryScreen.kt`, projector/VM tests and feature docs |
| Actions | On an effective sort or filter change, immediately scroll the loaded flat list to item zero; use `GymMotion.spatialFast()` for stable-key item placement. |
| Verification | targeted projector + ViewModel tests; `:app:compileDebugKotlin`; manual/code-review screen evidence because screen automation is waived |
| AC | AC-019 |

## File ownership and execution waves

One implementation writer owns all work: shared `ExerciseLibrary*`, `DomainModule`, tests and version are choke points, so no parallel implementation.

| File set | Sole task owner |
|---|---|
| shared history row, projector and projection test | T-001 |
| `WorkoutDao`, DAO test, catalog repository/interface/impl test and `DomainModule.kt` | T-002 |
| library ViewModel and VM test | T-003 |
| shell width policy, `MainScaffold`, graph and library screen | T-004 |
| version and tracker results | T-005 |

W1 T-001 → W2 T-002 → W3 T-003 → W4 T-004 → W5 T-005/final gates. Do not run Gradle while edits are active; run only named targeted command at a stable boundary.

## Quality gates

- Always: AC traceability; Room SSOT/UDF; handwritten fakes; no mocks/logs/dependencies/destructive fallback; final full unit suite then debug assembly once.
- Relevant: lifecycle-aware collection, loading/empty/content, SavedStateHandle and system Back, stable keys, semantics/48dp/2x font/adaptive, `WhileSubscribed`, compute dispatcher/cancellation.
- Relevant Room: targeted read-only `WorkoutDao` query test; no migration/schema test because entity/schema/version are unchanged. Not applicable: WorkManager/service, permissions/manifest/intents, network/retry, release assembly (no R8-sensitive change), charts.
- Final: `./gradlew :app:testDebugUnitTest`, `./gradlew :app:assembleDebug`, `git diff --check`, and version comparison with target.

## Risks, unresolved questions, rollback/data preservation

| Item | Treatment / residual risk |
|---|---|
| Parallel execution-variants work | High UI merge-conflict risk in `ExerciseLibrary*`, navigation, tests. Do not integrate variant model/search here; reconcile manually after merge. |
| History ordering | Resolved: narrow DAO projection uses `workouts.finishedAt`; `completedAt` cannot rank AC-007. It adds query/test maintenance but no data migration. |
| Sparse maps | Muscle maps supply search labels only at contribution `>=25` within the stored `ExerciseEntity.muscleGroup`; `CARDIO`/`FULL_BODY`, unmapped rows and mismatched maps remain normal flat catalog rows and never create cross-group filter membership. |
| Compact filters | Accessible scroll/surface and semantic 2x-font test; real-device owner walkthrough remains. |
| Rollback | Revert code/version only; no DB/Sheets write, so IDs/history/backups/round-trip are preserved. |

No unresolved product blocker or product question exists. The Feature Brief is updated and approved. Gate P: PASS — every AC has task/test evidence; contracts/dependencies/ownership/gates are explicit and non-overlapping.
