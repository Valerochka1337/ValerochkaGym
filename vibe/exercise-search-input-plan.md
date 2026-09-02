# План: быстрый ввод поиска упражнений

Статус: done. Slug: `exercise-search-input`. Основание: approved `exercise-catalog-tree-product.md` и Gate R от 2026-09-02.

## Goal, scope, non-goals, assumptions

Цель — сделать ввод в поиске библиотеки немедленным и устойчивым к задержке вычисления каталога, сохранив асинхронную compute-проекцию и уже выпущенные filter/sort/reset/SavedState-поведения.

Scope: `ExerciseLibraryViewModel`, `ExerciseLibraryScreen`, их VM regression test и обязательный patch version bump. Non-goals: Room/DAO/repository semantics, навигационные аргументы и pop, Hilt bindings/scopes, schema/migration, workers/services/permissions, зависимости или дизайн. Допущение: существующий `ExerciseCatalogRepository` — production SSOT каталога; этот фикс не меняет его содержимое и не требует UI automation, если VM regression надёжно воспроизводит планировщик.

## Acceptance criteria

| AC | Результат | Task / verification |
|---|---|---|
| AC-001 | Быстрый IME-ввод сразу отображается, без отката или потери фокуса. | T-001,T-002; VM production-path regression, targeted test |
| AC-002 | Последний query асинхронно проецирует каталог; задержка не меняет уже введённый текст. | T-001,T-002; controlled-dispatcher VM regression |
| AC-003 | Clear/reset, filters/sort и SavedState restoration сохраняют контракт. | T-001,T-002; existing + added VM tests |
| AC-004 | Тяжёлая проекция остаётся на `@ComputeDispatcher`. | T-001,T-002; controlled-dispatcher VM regression/review |

## Current and target flow

```
Current: repository SSOT + private query + filters + sort
      -> heavy combine (compute only in repository path) -> uiState.query -> OutlinedTextField

Target: repository SSOT + private mutable query -> read-only query: StateFlow -> Screen TextField
      \-> combine(catalogSource, query, filters, sort) -> asynchronous projection
          -> flowOn(@ComputeDispatcher) -> immutable uiState(results/filters/sort)
```

Room/repository remain SSOT for persisted catalog data. ViewModel owns query, saved presentation keys and cancellation in `viewModelScope`; compose owns focus and transient sheet/list state. User events remain `onQueryChange`, clear, reset, filter and sort. The screen collects both flows lifecycle-aware: query separately for the field, uiState for results. No domain state is written by search; route arguments and `SavedStateHandle` restoration stay unchanged.

## Architectural decisions and frozen contracts

- Expose the existing private query holder only as `StateFlow<String>` (`asStateFlow()`); the screen must use it as the sole `OutlinedTextField.value`. No composable-local query mirror, debounce, new event or navigation state.
- `ExerciseLibraryUiState.query` is not an input-control contract. Remove it if no longer needed, or retain it only as a derived projection value; no UI may bind editable text to it. Calculate reset visibility from the read-only query plus state filters so it cannot lag input.
- Keep the production `combine(catalogSource, query, filters, sort)` upstream of `flowOn(@ComputeDispatcher)` when the injected `ExerciseCatalogRepository` is present. The nullable DAO fallback exists only for direct-construction tests and retains its established scheduling; `stateIn(viewModelScope, WhileSubscribed(5_000), initial)` remains the cancellation owner and the final `StateFlow` query emission determines the settled result.
- `catalogSource` continues to be supplied by `ExerciseCatalogRepository` in production. Test doubles must provide a non-null fake repository, not exercise DAO fallback, to cover the exact compute path.
- UI stays Material 3 with existing strings, colors, shape and accessibility labels; there is no new layout, motion, haptic, adaptive or loading/empty/error/content visual contract. The fix must preserve existing focus naturally by not replacing the text field or altering its key.

## Tasks

### T-001 — Separate immediate query state from asynchronous catalog projection

| Field | Detail |
|---|---|
| Owner | Single implementation writer |
| Dependencies | — |
| Exact files | `app/src/main/java/com/valerochka1337/valerochkagym/ui/library/ExerciseLibraryViewModel.kt` |
| Actions | Publish read-only query flow; keep one private writer and existing SavedState writes. Preserve compute dispatch for the non-null production repository path, keep the direct-test fallback scheduling, and retain clear/reset/filter/sort semantics and `WhileSubscribed` ownership. Update derived constraint state so query need not await results. |
| Automated verification | `./gradlew :app:testDebugUnitTest --tests '*ExerciseLibraryViewModelTest'` |
| Done condition | A query assignment is observable from the read-only flow before the compute dispatcher runs; after the controlled dispatcher drains, catalog output matches the final query; no production hardcoded dispatcher or new coroutine scope exists. |
| AC | AC-001, AC-002, AC-003, AC-004 |

### T-002 — Bind the field to immediate state and cover the real path

| Field | Detail |
|---|---|
| Owner | Single implementation writer |
| Dependencies | T-001 frozen flow contract |
| Exact files | `app/src/main/java/com/valerochka1337/valerochkagym/ui/library/ExerciseLibraryScreen.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/ExerciseLibraryViewModelTest.kt` |
| Actions | Collect `viewModel.query` with lifecycle in the screen and pass it to `SearchField`; retain VM events and screen identity/focus. Add a private fake `ExerciseCatalogRepository` backed by `MutableStateFlow`, construct the VM with it plus a controlled compute dispatcher, attach a live `uiState` collector, and assert rapid `onQueryChange` calls immediately expose the final text while delayed projection ultimately filters for that final query. Add clear/reset and SavedState/filter/sort regression assertions on this production path. Add a minimal Compose test only if it can deterministically assert text/focus without introducing Android test infrastructure; otherwise record the VM coverage as the reliable layer. |
| Automated verification | `./gradlew :app:testDebugUnitTest --tests '*ExerciseLibraryViewModelTest'`; `./gradlew :app:compileDebugKotlin` |
| Done condition | `OutlinedTextField.value` comes only from read-only query flow; production-path tests fail under the old binding/conditional `flowOn` and pass with the fix; no existing behavior test is weakened. |
| AC | AC-001, AC-002, AC-003, AC-004 |

## File ownership and execution waves

One writer owns every implementation file: ViewModel and screen form the shared interaction choke point, and the regression test shares its constructor contract. No parallel implementer.

| File set | Sole owner |
|---|---|
| `ExerciseLibraryViewModel.kt` | T-001 |
| `ExerciseLibraryScreen.kt`, `ExerciseLibraryViewModelTest.kt` | T-002 |

Wave 1: T-001. Wave 2: T-002 and its targeted test. Wave 3: stable-diff final gates and tracker update. Do not run Gradle while another writer edits these files.

## Quality gates

- Targeted: `./gradlew :app:testDebugUnitTest --tests '*ExerciseLibraryViewModelTest'`, then `./gradlew :app:compileDebugKotlin` after the stable targeted diff.
- Relevant Android gates: immutable UDF; lifecycle-aware separate collection; `SavedStateHandle`; `WhileSubscribed(5_000)`; settled-result ordering; complete heavy projection under injected `@ComputeDispatcher`; handwritten private fakes and `MainDispatcherRule` + live collector. Review TextField semantics, 48dp existing actions, `fontScale=2.0`, compact/medium/expanded preservation; a Compose test is conditional on reliability.
- Not applicable: Room/schema/migration/transactions, repository/Hilt changes, navigation changes, WorkManager/services, permissions/manifest, network/retry, dependencies/R8/release and charts.
- Final project gates, once after code is stable: `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug`; `git diff --check`. This plan-only change does not run Gradle.

## Risks, unresolved questions, rollback/data preservation

| Item | Treatment |
|---|---|
| Old projected query is mistakenly reused in UI | Code review searches all `OutlinedTextField` bindings; test drives production repository path with paused compute dispatcher. |
| Earlier compute emission lags later input | Keep immediate query independent from projected results and assert the settled catalog corresponds to the final query. |
| State restoration regression | Retain key names and add production-path SavedState/filter/sort assertions. |
| Focus regression | Keep the same screen/text-field composition identity; optional Compose test only if deterministic. |
| Rollback/data preservation | Revert UI/VM/test only; no database, persisted catalog, repository contract or navigation data changes. |

Unresolved blocker: none. Gate P: PASS — every AC maps to task and automated verification; contracts are frozen; ownership is non-overlapping; only relevant conditional gates are listed.
