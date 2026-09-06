# План: дополнительный подход только для текущего упражнения

## Цель

Во время активной тренировки пользователь может добавить дополнительный подход только к упражнению, в котором находится текущий незавершённый подход. Во время неподтверждённой drag-and-drop перестановки действие временно скрыто; после подтверждения порядка Room оно появляется у нового текущего упражнения. ViewModel защищает операцию от устаревшего UI-вызова.

## Scope

- Показать TextButton `Подход` только в текущей незавершённой секции активной тренировки.
- Запретить `ActiveWorkoutViewModel.addSet(workoutExerciseId)` для любого id, кроме упражнения текущего `currentFocus()` из актуального Room-снимка.
- Сохранить существующую реализацию репозитория: транзакция, `max(setIndex) + 1`, копирование значений последнего подхода и новый незавершённый подход.
- Добавить минимальные регрессии UI и ViewModel и поднять версию приложения с `21` / `1.3.13` до `22` / `1.3.14`.

## Non-goals

- Изменение Room-схемы, DAO, миграций, репозитория, DI/Hilt, навигации, foreground service, WorkManager, разрешений или фоновой работы.
- Изменение правила выбора текущего подхода `WorkoutFull.currentFocus()`.
- Добавление нового состояния, события, зависимости, строкового ресурса, анимации или хаптики.

## Assumptions

- Утверждённый продуктовый выбор: `currentFocus()` выбирает первый незавершённый подход первого упражнения в текущем порядке; при отсутствии фокуса дополнительный подход недоступен.
- `ActiveWorkoutContent` остаётся владельцем краткоживущего `localOrder`; после recreation он восстанавливается из персистентного Room-порядка. Новое saveable-состояние не нужно.
- Репозиторный тест уже доказывает AC-004, поэтому код и тесты data-слоя не меняются.

## Acceptance criteria

| ID | Критерий |
|---|---|
| AC-001 | Текущий фокус остаётся первым незавершённым подходом первого незавершённого упражнения в отображаемом порядке. |
| AC-002 | `Подход` виден только у упражнения текущего фокуса; у других упражнений и при отсутствии фокуса кнопки нет. |
| AC-003 | `ActiveWorkoutViewModel.addSet(id)` ничего не записывает, если `id` не принадлежит текущему фокусному упражнению либо активной тренировки/фокуса нет. |
| AC-004 | Разрешённое добавление использует неизменённый репозиторный контракт: одна транзакция, следующий `setIndex`, копия последнего подхода и `isCompleted = false`. |
| AC-005 | Пока локальная перестановка не подтверждена Room, `Подход` скрыт во всех секциях; после подтверждения он виден только у текущего упражнения нового порядка. |
| AC-006 | Версия feature-сборки равна `versionCode = 22`, `versionName = 1.3.14`. |

## Current and target flow

**Current:** Room `observeActive()` → `ActiveWorkoutViewModel.uiState` → `ActiveWorkoutContent`; экран строит `exercises` из `localOrder`, вычисляет `currentFocus()`, но всегда рисует `Подход` и ViewModel безусловно вызывает `repository.addSet(id)`.

**Target:** тот же SSOT Room → Flow → immutable `ActiveWorkoutUiState` → Compose. Пока `localOrder` отличается от Room-порядка, `ActiveWorkoutContent` не рисует `Подход`, так как ViewModel намеренно авторизует запись только по последнему Room-снимку. После подтверждения порядка Room экран вычисляет `currentFocus()` и передаёт в секцию boolean eligibility: только совпавшая секция рисует `Подход`. Нажатие передаёт id вверх. ViewModel повторно получает фокус из последнего `activeWorkout.value`, находит его `workoutExercise.id` и запускает repository write только при точном совпадении. Room эмитит новое дерево; экран обновляет карточки. Локальный порядок остаётся UI-only и не становится вторым persisted SSOT.

## Architectural decisions and frozen contracts

- `WorkoutFull.currentFocus()` — единый доменный алгоритм; его контракт и файлы не меняются. UI обязан вызывать его над локально упорядоченным списком, ViewModel — над последним Room-снимком.
- `ActiveWorkoutUiState` и `ActiveWorkoutEvent` не расширяются. Add-set остаётся fire-and-forget user event без ошибки и без навигации; отклонённый запрос является no-op.
- Сигнатура `ActiveWorkoutViewModel.addSet(workoutExerciseId: Long)` и `SetActions.addSet` не меняется. Внутри ViewModel до `viewModelScope.launch` вычисляется допустимый id из `activeWorkout.value`; при несовпадении немедленный return. Это оставляет отмену child coroutine в `viewModelScope` прежней и не создаёт собственного dispatcher/scope.
- `ExerciseSection` получает явно вычисленный флаг (например, `showAddSet`) и создаёт TextButton только при `true`; не вычисляет фокус сам и не хранит состояние. Семантика кнопки остаётся доступной через текст `Подход`; декоративная Add-иконка остаётся с `contentDescription = null`.
- `ActiveWorkoutRepository.addSet` остаётся единственным writer для вставки. Его Room `withTransaction`, copy/index semantics и data ownership не меняются; никаких миграций, схем или data-preservation действий нет.
- Навигация, state restoration, Hilt bindings/scopes, permissions, service/worker scheduling и adaptive shell не затронуты. Existing loading/empty/error/content UI остаётся прежним: loading/empty не вызывают `ActiveWorkoutContent`, а content без focus скрывает кнопку.
- UI меняет только видимость уже существующего второстепенного TextButton: сохранить 48dp touch target, Material/GymMotion/GymHaptics usage без новых токенов, и проверить semantics, fontScale 2.0 и compact/medium/expanded через existing layout policy as a manual review point.

## Tasks

### T-001 — Gate UI eligibility by local display order

| Field | Detail |
|---|---|
| Owner | One implementation writer |
| Files | `app/src/main/java/com/valerochka1337/valerochkagym/ui/active/ActiveWorkoutScreen.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/active/ActiveWorkoutScreenTest.kt` |
| Depends on | Frozen contracts above |
| Actions | Derive the focus exercise id from the existing local-order `currentFocus`; pass a boolean into each `ExerciseSection`; conditionally compose `Подход` only after `localOrder` equals the Room order. Extend the Compose/Robolectric test to prove one visible button for the focused exercise, none after all sets finish, no button during a pending local reorder, and exactly one focused button after Room confirms the new order. |
| Automated verification | `./gradlew :app:testDebugUnitTest --tests "*ActiveWorkoutScreenTest"` |
| Done condition | The `Подход` node is present exactly once only for the acknowledged-order focus, is absent with no focus or pending reorder, and the targeted test passes. |
| AC mapping | AC-001, AC-002, AC-005 |

### T-002 — Defend add-set at the ViewModel boundary

| Field | Detail |
|---|---|
| Owner | One implementation writer |
| Files | `app/src/main/java/com/valerochka1337/valerochkagym/ui/active/ActiveWorkoutViewModel.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/ActiveWorkoutViewModelTest.kt` |
| Depends on | Frozen `currentFocus()` and repository contract; T-001 is independent but should land first so UI and VM use the same product rule. |
| Actions | Gate `addSet` using the current Room-backed active snapshot and the owning `WorkoutExerciseEntity.id` of its focus set. Keep a matching request forwarded once. Update the handwritten fake/harness and split the structure-edit test into focused allowed and nonfocused/no-workout/no-focus no-op assertions using a two-exercise fixture. |
| Automated verification | `./gradlew :app:testDebugUnitTest --tests "*ActiveWorkoutViewModelTest" --tests "*ActiveWorkoutRepositoryTest"` |
| Done condition | A matching current-exercise id records one repository call; other exercise ids, null workout, and completed workout record none; existing repository copy/index regression remains green. |
| AC mapping | AC-003, AC-004 |

### T-003 — Version and stable feature validation

| Field | Detail |
|---|---|
| Owner | One implementation writer (shared build choke point) |
| Files | `app/build.gradle.kts` |
| Depends on | T-001, T-002 |
| Actions | Increase only default `versionCode` and patch `versionName` to 22 / 1.3.14. After the implementation diff is stable, run the final project gates once and record exact results in the tracker. |
| Automated verification | `./gradlew :app:testDebugUnitTest`; then `./gradlew :app:assembleDebug` |
| Done condition | The version values are exact and both final commands pass. |
| AC mapping | AC-006; final regression evidence for AC-001…AC-005 |

## File ownership

| Owner | Exclusive files |
|---|---|
| One implementation writer | `ui/active/ActiveWorkoutScreen.kt`, `ui/active/ActiveWorkoutViewModel.kt`, `ui/active/ActiveWorkoutScreenTest.kt`, `ui/ActiveWorkoutViewModelTest.kt`, `app/build.gradle.kts` |
| Existing data owner — no edit | `data/ActiveWorkoutRepositoryImpl.kt`, `domain/ActiveWorkoutRepository.kt`, `domain/SessionFocus.kt`, existing `domain/ActiveWorkoutRepositoryTest.kt` |

## Execution waves

1. **Wave 1:** one writer executes T-001 and T-002 sequentially in the same checkout; no parallel writer because both consume the frozen focus contract and share active-workout behavior.
2. **Wave 2:** same writer executes T-003 and runs targeted tests after edits are stable.
3. **Wave 3:** tester owns Gradle final gates and reviewer is read-only; no production edits during these commands.

## Quality gates

**Applicable conditional gates:** immutable state down/events up; Room remains SSOT; lifecycle-aware `StateFlow` / `WhileSubscribed(5000)` pattern remains; no hardcoded dispatcher or new scope; Compose accessibility semantics, touch target, font scale 2.0, local-state restoration and compact/medium/expanded review; targeted JUnit/Robolectric tests with handwritten fakes and `MainDispatcherRule`.

**Not applicable:** Room migration/schema, DI scope/binding, dependency/R8/release, navigation route/back-stack, permissions/manifest, WorkManager/service behavior, chart rendering.

**Final project gates:** after a stable code diff, sequentially run `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug` once. `assembleRelease` is not required because no dependency, manifest, resource-shrinking or release-sensitive behavior changes.

## Risks, questions, rollback, data preservation

- **Risk:** a stale UI tap can arrive after focus changes. The ViewModel guard safely turns it into a no-op; the UI guard is only the immediate affordance.
- **Risk:** `Подход` is temporarily unavailable while a drag reorder waits for Room. This avoids issuing an action whose target has not yet reached the single persisted source of truth; it returns after the Room acknowledgement. If persistence fails, reopening the screen restores the persisted order and its eligible action.
- **Unresolved questions:** none; product behavior is approved.
- **Rollback/data preservation:** revert only UI/ViewModel/version changes. The feature creates no schema or durable-format change; pre-existing extra sets and completed workout data remain untouched. An in-flight permitted repository insertion retains its existing atomic transaction behavior.

## Gate P self-check

Pass. AC-001…AC-006 each map to T-001/T-002/T-003 and an automated verification. The SSOT, UI/VM contracts, coroutine ownership, and non-applicable Android surfaces are explicit. One writer owns every mutable implementation file; no ownership overlaps. No Room/Hilt/navigation/shared build catalog work is split. Conditional gates are limited to UI/state/test behavior relevant to this change.
