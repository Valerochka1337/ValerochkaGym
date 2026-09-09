# Delivery-план: сохранение программы после тренировки (#45/#46, этапы 01–02)

Статус: strict-подготовка. Gate R принят в [workout-program-save-brief.md](workout-program-save-brief.md); в этой работе код, тесты, Gradle, схема и версия не менялись.

## Цель, scope, non-goals и допущения

После завершённой тренировки единый сценарий итогов позволяет создать новую личную программу из выполненных подходов либо перезаписать текущую личную программу. Составная запись атомарна, STANDARD защищён. Scope: summary UI/state, оба use case, typed repository contract/Room transaction и перечисленные тесты; #45/#46 — один выпуск.

Non-goals: entity/DAO/schema/migration (Room 16), правила упражнений/оборудования, `GymNavGraph`, `CatalogSchema`, worker/Hilt/dependency/manifest/service changes, #44/#47, backend merge. Один version bump всего выпуска делается после fixes review и до final stable diff.

- `WorkoutFull` — immutable input; только completed sets в порядке exercise position → `setIndex`.
- Create: fresh UUID операции = `syncId` новой PERSONAL-программы, `restSeconds=null`, без `routine_gyms`; источник и история неизменны.
- Replace: только existing PERSONAL; сохраняет `routineId`, `syncId`, `note`, `origin`, rest оставшихся упражнений; children заменяются одной транзакцией.
- STANDARD/deleted/unavailable/missing source не replaceable; при done sets create доступен.
- **Q-001 не принят:** при нуле done sets владелец выбирает обычный выход без save entry либо explanation без destructive action. Рекомендован первый вариант, но это не решение.
- `SavedStateHandle` восстанавливает шаг, имя/error, UUID, source ID, `expectedUpdatedAt` и input fingerprint при обычном saved-state restore. Он не гарантирует произвольный force-stop/task removal/process death до записи handle; Room — SSOT.

## Приёмка

- **AC-001:** Done открывает выбор до записи; dismiss/Back/выход не меняет программу/историю.
- **AC-002:** create даёт ровно одну PERSONAL с fresh operation `syncId`, done sets, null rest и без gyms; source неизменен.
- **AC-003:** replace атомарно даёт только done sets (4 planned → 3 done = 3), сохраняет ID/syncId/rest оставшихся и существующие `routine_gyms`.
- **AC-004:** STANDARD/deleted/unavailable/missing source не replaceable в UI и transaction; create с done sets доступен.
- **AC-005:** Saved/read-only/not-found/conflict/failure различимы; `CancellationException` пробрасывается; scheduler failure после commit не создаёт дубль.
- **AC-006:** repeated confirm и saved-state recreation не создают дубль; граница восстановления явна.
- **AC-007:** zero done sets не очищает программу и не создаёт пустую; UX — Q-001.
- **AC-008:** choice/name/replace-confirm/saving/error/Back сохраняют ввод/понятное состояние; semantics, ≥48dp, fontScale 2.0, insets, compact/medium/expanded.

## Поток и frozen contracts

Текущий `WorkoutSummaryViewModel` имеет ранний update dialog и create-only dialog; `RoutineUpdateUseCase` пишет отдельно; `SaveCompletedWorkoutAsRoutineUseCase` уже маппит done sets и использует transaction-аналог create. Цель: immutable `WorkoutSummaryUiState` ← events → ViewModel (`Mutex`, `SavedStateHandle`, `viewModelScope`) → create/replace use case → typed `GymRepository` command → `GymRepositoryImpl.withTransaction` → Room aggregate SSOT → result → state/one-shot event. Scheduler только после commit.

1. Один typed command/result обслуживает create/replace. Транзакция повторно читает source, проверяет origin/existence/availability, валидирует aggregate и пишет всё либо ничего. `WorkoutEntity.routineId`, `SET_NULL`, история не меняются.
2. Create retry: UUID = unique new routine `syncId`; replay возвращает существующий target.
3. **Replace retry на existing schema:** command содержит `sourceRoutineId`, `sourceRoutineSyncId`, `expectedUpdatedAt`, canonical `expectedSourceFingerprint`, canonical `predictedTargetFingerprint` и `operationUuid` только для UI context. Fingerprint всегда сравнивается до любой перезаписи: parent `routineId/syncId/name/note/origin/archived`; ordered children `exerciseId/position/restSeconds/plannedSets`; stable-sorted gym `syncId`. Generated child IDs исключены, `updatedAt` проверяется отдельно. В transaction: source absent → `NotFound`; identity/origin не совпадает → `ReadOnly`/`Conflict`; current равен predicted → `Saved(replayedWithoutWrite)`; иначе current равен expected **и** `updatedAt==expectedUpdatedAt` → atomic replace; иначе → `Conflict` без записи. Это защищает child-only mutation, который может не менять parent `updatedAt`. Нет durable journal/новой schema/force-stop promise.
4. Result: `Saved(identity)`, `ReadOnly`, `NotFound`, `Conflict(availability/version)`, `Failure`. Ловить только ожидаемые DB failures, `CancellationException` rethrow.
5. Commit — успех. `RoutineUploadScheduler` один раз после commit для saved/replayed `syncId`; исключение изолировано. Новый worker/work name/scope/retry policy не добавлять.
6. ViewModel владеет immutable sealed step `Choice/CreateName/ReplaceConfirm/Saving/retryable error`; Handle хранит только draft/context, snackbar/haptic — Channel/Flow. Существующие Hilt binding/navigation/schema read-only; schema discovery требует amendment и одного owner entity+DAO+migration+DB+schemas+tests.
7. UI: `AlertDialog`, `PillButton`, `GymCard`, `GlowBackground`, `GymMotion`, `gymHaptics`; русские строки Kotlin, `MaterialTheme.colorScheme.*`.

## Задачи

| Task | Точные файлы | Owner / зависимости | Действие | Автопроверка | Done condition | AC |
|---|---|---|---|---|---|---|
| T-001 Зафиксировать Q-001 | `vibe/workout-program-save-plan.md`; `vibe/workout-program-save-plan-track.md` | Product owner + planner / Gate R | Записать выбранный zero-completed UX; replace contract уже frozen. | `rg -n "Q-001|AC-00[1-8]|T-00[1-7]" vibe/workout-program-save-plan*.md` | Выбран Q-001; до этого не реализуется только зависимая UI-ветка. | AC-007 |
| T-002 Typed atomic repository boundary | `app/src/main/java/com/valerochka1337/valerochkagym/domain/GymRepository.kt`; `app/src/main/java/com/valerochka1337/valerochkagym/data/GymRepositoryImpl.kt`; `app/src/main/java/com/valerochka1337/valerochkagym/data/db/dao/RoutineDao.kt` (только минимальный query при нужде); `app/src/test/java/com/valerochka1337/valerochkagym/data/GymRepositoryImplTest.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/data/RoutineDaoTest.kt` (только при DAO change) | Один implementer / frozen contract | Command/results, transaction validation, source identity + expected/predicted fingerprints перед overwrite, rollback/history/cancellation. | `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests "*GymRepositoryImplTest" --tests "*RoutineDaoTest"` | Real Room: обе 4→3 ветки, STANDARD/not-found/availability/version conflict, replayWithoutWrite, child-only mutation с тем же parent `updatedAt` → Conflict/no write, ID/syncId/rest/gyms/history и no partial aggregate. | AC-002–005,007 |
| T-003 Create/replace use cases | `app/src/main/java/com/valerochka1337/valerochkagym/domain/SaveCompletedWorkoutAsRoutineUseCase.kt`; `app/src/main/java/com/valerochka1337/valerochkagym/domain/RoutineUpdateUseCase.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/domain/SaveCompletedWorkoutAsRoutineUseCaseTest.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/domain/RoutineUpdateUseCaseTest.kt` | Один implementer / T-002 | Переиспользовать done-set mapper/order, typed outcomes, post-commit scheduler, cancellation/create+replace replay. | `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests "*SaveCompletedWorkoutAsRoutineUseCaseTest" --tests "*RoutineUpdateUseCaseTest"` | Fake tests: 4→3, no empty target, result distinctions, create idempotency, scheduler failure без второго create. | AC-002–007 |
| T-004 Summary state/UI | `app/src/main/java/com/valerochka1337/valerochkagym/ui/summary/WorkoutSummaryViewModel.kt`; `app/src/main/java/com/valerochka1337/valerochkagym/ui/summary/WorkoutSummaryScreen.kt`; `app/src/main/java/com/valerochka1337/valerochkagym/ui/components/SaveWorkoutAsProgramDialog.kt` (если API не выражает states); `app/src/test/java/com/valerochka1337/valerochkagym/ui/WorkoutSummaryViewModelTest.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/summary/WorkoutSummaryScreenTest.kt` | Один implementer / T-001,T-003 | Единый choice→name/confirm→saving/result, draft/context restore, mutex, errors, approved Q-001, semantics/design system. | `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests "*WorkoutSummaryViewModelTest" --tests "*WorkoutSummaryScreenTest"` | `MainDispatcherRule`/`runTest`/live collector доказывают retry/recreation/cancellation; screen states/semantics/48dp/Q-001 проходят. | AC-001,004–008 |
| T-005 Strict targeted verification/review | Только `vibe/workout-program-save-plan-track.md` | Tester (единственный Gradle owner) + read-only Sol/high reviewer / stable T-002–004 | Использовать уже валидное targeted evidence; запускать только coverage gaps или invalidated areas. Reviewer читает atomicity/replay/conflict/cancellation/restoration/UI и возвращает один findings пакет. | Только команды для найденного gap/invalidated area, с JDK 21. | Evidence/findings пакет и review verdict зарегистрированы; P0/P1 допускаются как вход T-006; нет незафиксированного scope drift. | AC-001–008 |
| T-006 Fix findings + version | Уже назначенные T-002–004 production/test files; `app/build.gradle.kts`; tracker | Один implementer / T-005 | Исправить findings, после остановки writer выполнить affected-findings read-only recheck и только нужный targeted test; затем сверить target main, поднять `versionCode` и patch ровно раз до final stable diff. | Recheck owner read-only; targeted test owner запускает только проверку затронутого finding с `JAVA_HOME="$(/usr/libexec/java_home -v 21)"`; final pair принадлежит T-007. | Findings rechecked, P0/P1 отсутствуют, final diff содержит fixes и version строго выше main. | AC-001–008 |
| T-007 Final gates/handoff | `vibe/workout-program-save-plan-track.md` | Final agent / T-006 | Один final run, записать commands/outcomes/AC/deviations/risks. | `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest`; затем `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:assembleDebug` | Обе прошли на final diff; P0/P1 нет. | AC-001–008 |

## Владение и waves

Один implementer владеет T-002–004/T-006 и всеми указанными файлами, включая `app/build.gradle.kts`. T-001 — product decision. T-005: tester единственный Gradle owner, Sol/high reviewer read-only. Parallel implementers нет.

| Wave | Owner | Работа | Gradle |
|---|---|---|---|
| 0 | Owner + planner | T-001 | Нет |
| 1 | Один implementer | T-002 → T-003 → T-004 | Targeted после stable writes |
| 2 | Tester + read-only reviewer | T-005 | Только tester, лишь gaps/invalidated areas |
| 3 | Один implementer, затем read-only reviewer + targeted-test owner | T-006 до final stable diff | Только targeted-test owner, лишь affected finding |
| 4 | Final agent | T-007 | Только final agent |

## Quality gates, риски и Gate P

Релевантны: один `:app`; UDF/immutable/lifecycle; cancellation/race/retry; Room transaction/rollback/trigger/FK/history без destructive migration; semantics/fontScale 2.0/≥48dp/non-color errors/adaptive/insets/Back; post-commit scheduler; один version bump. Charts, permissions, manifest, services, dependencies/R8/release, migration/schema не затронуты; любое изменение требует amendment/gate.

Единственный product blocker — Q-001: подготовка и strict review продолжаются, но его UI-ветка ждёт решения. Source identity + обязательные expected/predicted full fingerprints до overwrite безопасно различают replay без записи и concurrent conflict, включая child-only mutation при неизменном parent `updatedAt`; это не durable journal и не force-stop guarantee. Transaction-time source validation обязательна. Rollback сохраняет source aggregate/историю; нет fallback/migration/trigger edit. Чужие untracked `vibe/` файлы сохранять.

Gate P: все AC имеют T и verification, T-001…007 — status-row; ownership не пересекается; contracts atomicity/IDs/replay/errors/cancellation/sync/history/STANDARD/restoration заморожены; version до единственного final run. Independent strict review (Sol/high, 10.09.2026) и affected-findings recheck завершены: **conditional pass**, все три технических finding устранены. Q-001 остаётся единственной условной product detail. Код не реализовывался, проверки приложения не запускались.

## Старт реализации по поручению владельца

Владелец разрешил автономно реализовать весь согласованный план, выполнять Git-публикацию и
проверить успешный релиз. Прежний статус «только подготовка» выше — история подготовки.
Q-001 разрешён исполнителем в рамках этой делегации: при нуле выполненных подходов «Готово»
выходит без сохранения/перезаписи программы; завершённая история остаётся. Это сохраняет
существующие данные и не навязывает пустую программу. Gate P допускает реализацию.
