# План: фасетный каталог упражнений

Статус: готов к реализации. Slug: `exercise-catalog-tree`. Основание: согласованный [Feature Brief](exercise-catalog-tree-product.md), AC-001…AC-016.

## Goal, scope, non-goals, assumptions

Цель — дать существующей библиотеке офлайн-проекцию базовых упражнений: непересекающиеся «Недавние»/«Частые», путь `крупная группа → конкретная мышца → результаты`, поиск и фасеты; пути не меняют ID, историю или picker-контракт.

В scope: direct `>=60`, secondary `25..59`, поиск по имени/русским label группы, мышцы и типа; фасеты group/muscle/type/origin; alphabet/recent/frequent; loading/empty/content, TalkBack, `fontScale=2.0`, compact/medium/expanded. Ограниченный залами поток применяется до любой проекции. Не входят: изменение Room schema/entity/migration, Sheets/sync, сеть/permissions/workers/services, dependencies, equipment/movement patterns/synonyms, variants/variant search, новые routes и telemetry. Единственное Room-касание — узкая read-only DAO-проекция истории для уже существующих строк.

Допущения: recent = максимальный `WorkoutEntity.finishedAt` из новой узкой finished-only истории `(exerciseId, workoutId, finishedAt)`; `completedAt` подхода для AC-007 не применяется. Frequent = число разных `workoutId`, не подходов. Quick sections максимум по 5, Frequent исключает показанные Recent IDs; UI допускает один выбор на фасет (AND между фасетами); query/facets/sort/level восстанавливаются из `SavedStateHandle` и исчезают с pop route.

## Acceptance criteria and traceability

| AC | Result | Task / automated evidence |
|---|---|---|
| AC-001 | unique quick recent/frequent sections | T-001,T-003,T-004; projection/VM/screen tests |
| AC-002 | no-history hides quick sections | T-001,T-004; projection/screen tests |
| AC-003 | group→muscle direct/secondary, ignore `<25` | T-001,T-003,T-004; projection/VM/screen tests |
| AC-004 | multi-path retains one base record/history | T-001,T-004; projection/screen tests |
| AC-005 | case-insensitive name/group/muscle/type search | T-001,T-003; projection/VM tests |
| AC-006 | AND facets and no duplicates | T-001,T-003; projection/VM tests |
| AC-007 | recent excludes active and orders by workout `finishedAt` | T-001,T-002,T-003; DAO/projection/repository/VM tests |
| AC-008 | frequent counts distinct completed workouts | T-001,T-002,T-003; projection/repository/VM tests |
| AC-009 | gym intersection precedes all surfaces | T-002,T-003; repository/VM plus `*GymDaoTest`, `*GymRepositoryImplTest` |
| AC-010 | saved custom map updates live | T-002,T-003; VM test |
| AC-011 | unmapped entry remains list/search/group/type only | T-001,T-004; projection/screen tests |
| AC-012 | explanatory empty + single reset | T-003,T-004; VM/screen tests |
| AC-013 | picker back/cancel has no write | T-003,T-004; `*ActiveWorkoutViewModelTest`, `*RoutineEditorViewModelTest`, screen test |
| AC-014 | local browse/filter/select works offline | T-002,T-003; repository/VM test |
| AC-015 | semantic state/actions, 48dp and 2x font | T-004; screen Compose semantics test |
| AC-016 | IDs/history/Room/Sheets unchanged | T-001,T-002,T-005; regressions/final gates |

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
- Pure `ExerciseCatalogProjector` receives available `ExerciseEntity`, muscle map and `ExerciseWorkoutHistoryRow`; all output retains original `ExerciseEntity.id`. Top-level groups are exclusively `ExerciseEntity.muscleGroup`, never inferred from `Muscle.group()`. For a normal selected group, expose an explicit “all group exercises” result (so unmapped/mismatched entries remain reachable) plus only mapped muscles with contribution `>=25` and `muscle.group() == selectedGroup`. `CARDIO` and `FULL_BODY` expose only “all group exercises”, no fabricated concrete muscle. A custom map whose muscles disagree with its stored group remains in that stored top group and never leaks into another group’s muscle leaf.
- Immutable `ExerciseLibraryUiState`: nullable projection (loading), query, filters, sort, hierarchy level, gym labels, derived visible surface/count/empty reason. Explicit events: query/facet/sort changes, `OpenGroup`, `OpenAllGroupExercises`, `OpenMuscle`, `Back`, `Reset`, existing editor events. Normalize restored level against each new available projection: missing group/muscle falls back to its valid group or overview before rendering. Save only presentation keys to route `SavedStateHandle`, never Room/DataStore.
- Existing `GymRoutes.LIBRARY` args remain. Screen-level Back ascends muscle→group→overview then invokes existing pop. Existing `SELECTED_EXERCISE_ID`, picker callback and `create/updateExercise...AddToWorkout` atomic writes remain the only selection writes.
- Projection is upstream of `stateIn`, runs with `flowOn(@ComputeDispatcher)`, is cancelled by `viewModelScope`; no `GlobalScope`, network, worker/service, permission, logging or dependency.
- UI uses existing `GlowBackground`, `GymCard`, `GymFilterChip`, `FadeInContent`, `GymMotion`, `gymHaptics`, Material colors/shapes, Kotlin strings and stable Lazy keys. It supplies heading/level, selected state, reset/up labels and >=48dp targets. A project-owned `GymWindowWidthClass` (`Compact <600dp`, `Medium 600..<840dp`, `Expanded >=840dp`) is computed once from the shell's available width in `MainScaffold` and passed through `GymNavGraph`: compact uses a 16dp single column; medium/expanded use 24dp gutters and one centered max-840dp column (no list-detail because choosing remains a pop/detail action). This adds no dependency. Facets remain horizontally scrollable rather than clipped at 2x font.

## Tasks

### T-001 — Pure catalog projection

| Field | Detail |
|---|---|
| Owner | Implementation writer |
| Dependencies | — |
| Exact files | `app/src/main/java/com/valerochka1337/valerochkagym/data/db/relation/ExerciseWorkoutHistoryRow.kt` (new); `app/src/main/java/com/valerochka1337/valerochkagym/domain/ExerciseCatalogProjector.kt` (new); `app/src/test/java/com/valerochka1337/valerochkagym/domain/ExerciseCatalogProjectionTest.kt` (new) |
| Actions | Define the shared pure history-input row `(exerciseId, workoutId, finishedAt)` before any consumer, then immutable snapshot/projection/filter/sort/level models and pure mapping. Cover quick-section exclusion/no-history, thresholds/multi-path stable IDs, search labels/case, AND/dedupe, `finishedAt` ranking input, normal/CARDIO/FULL_BODY top groups, unmapped and mismatched custom maps. |
| Automated verification | `./gradlew :app:testDebugUnitTest --tests '*ExerciseCatalogProjectionTest'` |
| Observable done condition | Output preserves base IDs; no `<25` or cross-top-group muscle leaf; CARDIO/FULL_BODY have only all-group results; deterministic tests cover AC-001…008 and AC-011. |
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
| Actions | Inject repository and `@ComputeDispatcher`; project upstream of `stateIn(WhileSubscribed(5000))` using DAO `finishedAt` history. Replace group-only StateFlow with saved state/events/level Back/reset and normalize stale restored group/muscle levels when availability changes. Preserve direct-test NoOp editor behavior, AI cancellation and atomic picker save. Use private handwritten fakes plus live collector. |
| Automated verification | `./gradlew :app:testDebugUnitTest --tests '*ExerciseLibraryViewModelTest' --tests '*ActiveWorkoutViewModelTest' --tests '*RoutineEditorViewModelTest'` |
| Observable done condition | recreation restores valid presentation; removed group/muscle safely falls back before render; catalog/map/history updates live; reset writes nothing; only overview Back delegates pop; cancel does not emit selection. |
| AC | AC-001…AC-014, AC-016 |

### T-004 — Facet UI and accessibility

| Field | Detail |
|---|---|
| Owner | Implementation writer |
| Dependencies | T-003 |
| Exact files | `app/src/main/java/com/valerochka1337/valerochkagym/ui/navigation/GymWindowWidthClass.kt` (new); `app/src/main/java/com/valerochka1337/valerochkagym/ui/navigation/MainScaffold.kt`; `app/src/main/java/com/valerochka1337/valerochkagym/ui/navigation/GymNavGraph.kt`; `app/src/main/java/com/valerochka1337/valerochkagym/ui/library/ExerciseLibraryScreen.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/ExerciseLibraryScreenTest.kt` (new) |
| Actions | Render stored-group overview, all-group and permitted muscle levels, direct/secondary results, neutral quick sections, counted filtered result and resettable empty/loading states. Compute the explicit project width class once in `MainScaffold` from its available width and thread it through graph to screen: compact 16dp; medium/expanded centered max-840dp with 24dp gutters; scroll facet controls. Test width-class mapping and screen semantics/state/actions/reset/picker/cancel for compact/medium/expanded plus 2x-font constraints without screenshots. |
| Automated verification | `./gradlew :app:testDebugUnitTest --tests '*ExerciseLibraryScreenTest' --tests '*ExerciseLibraryViewModelTest' --tests '*AdaptiveNavigationTest'` |
| Observable done condition | No unavailable/empty/cross-group muscle branch, non-color state/action labels, >=48dp targets; all three width contracts and 2x font have test assertions; info opens detail and primary picker tap selects. |
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

## File ownership and execution waves

One implementation writer owns all work: shared `ExerciseLibrary*`, `DomainModule`, tests and version are choke points, so no parallel implementation.

| File set | Sole task owner |
|---|---|
| shared history row, projector and projection test | T-001 |
| `WorkoutDao`, DAO test, catalog repository/interface/impl test and `DomainModule.kt` | T-002 |
| library ViewModel and VM test | T-003 |
| shell width policy, `MainScaffold`, graph, library screen and screen test | T-004 |
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
| Sparse maps | Unmapped entries remain full/search/group/type; only concrete muscle leaves omit them. |
| Compact filters | Accessible scroll/surface and semantic 2x-font test; real-device owner walkthrough remains. |
| Rollback | Revert code/version only; no DB/Sheets write, so IDs/history/backups/round-trip are preserved. |

No unresolved product blocker exists. Product brief needs the same clarified history/top-group/adaptive wording applied by its owner because it is outside this planner's writable-file allowance. Gate P: PASS — every AC has task/test evidence; contracts/dependencies/ownership/gates are explicit and non-overlapping.
