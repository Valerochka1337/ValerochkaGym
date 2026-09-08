# Трекер: шаблоны и карточка зала

## Задачи

| Task | Status | Owner | AC | Проверка |
|---|---|---|---|---|
| T-001 | done | Editor writer | AC-004, AC-005, AC-006, AC-007 | `:app:testDebugUnitTest --tests "*GymEditorViewModelTest" --tests "*GymEditorScreenTest" --tests "*ExpandedGymEditorScreenTest"` |
| T-002 | done | List/detail/nav writer | AC-001, AC-002, AC-003, AC-004, AC-007 | `:app:testDebugUnitTest --tests "*GymRoutesTest" --tests "*GymDetailViewModelTest" --tests "*GymDetailScreenTest" --tests "*WorkoutsScreenTest" --tests "*GymsScreenTest"` |
| T-003 | done | Main/integrator | AC-001…AC-007 | `GymRepositoryImplTest`; targeted suite; `:app:testDebugUnitTest`; `:app:assembleDebug` |

## AC → task → test

| AC | Task | Автоматическое evidence |
|---|---|---|
| AC-001 | T-002 | `WorkoutsScreenTest`, `GymsScreenTest` |
| AC-002 | T-002 | `WorkoutsScreenTest`, `GymsScreenTest` |
| AC-003 | T-002 | `GymRoutesTest`, `GymDetailViewModelTest`, `GymDetailScreenTest` |
| AC-004 | T-001, T-002 | `GymEditorScreenTest`, `GymsScreenTest`, `GymDetailScreenTest` |
| AC-005 | T-001 | `GymEditorViewModelTest` |
| AC-006 | T-001 | `GymEditorViewModelTest`, `GymEditorScreenTest`, `ExpandedGymEditorScreenTest` |
| AC-007 | T-001, T-002, T-003 | existing `GymEditor*`, focused list/detail tests, `GymRepositoryImplTest`, final unit suite |

## Deviations

Продуктовых отклонений нет. Существующее копирование личного зала сохранено в меню карточки.
AC-001…AC-007 выполнены; версия повышена один раз: `28 / 1.3.20` → `29 / 1.3.21`.
Ветка: `feat/templates-gym-details`, база: `80d4865` (`main`).

## Findings

- Configured gym очищает legacy `gym.exercises`; доступность detail берёт только `GymRepository.observeAvailableExercises(setOf(gymId))`.
- Исправлено восстановление `DRAFT_LOADED`: origin повторно проверяется по репозиторию, стандартный зал не принимает устаревший личный черновик.
- Существующая копия standard gym — `gymEditor(copySourceGymId = id)`; backend/sync API не нужен.

- Исправлены размещение `catch` для повторной загрузки карточки и разрешение названий оборудования через реактивный `LocalEquipmentCatalog`.
- Во время сохранения личный редактор сохраняет форму с отключёнными действиями.
- Независимое ревью: P0/P1 нет. Единственное P2 по восстановлению раскрытия залов закрыто тестом реального `GymsScreen` через `StateRestorationTester`; повторная проверка подтверждает закрытие.
- Добавлены compact 320dp/fontScale 2 проверки шаблонов, expanded 1200dp/fontScale 2 проверки действий карточки и прокрутки. Редактор проверен на compact/expanded при fontScale 2.

## Command results

- `git diff --check` — успешно после реализации.
- `./gradlew spotlessApply` — успешно.
- Общий targeted gate: 45 тестов, первоначально 2 ошибки тестовых fixtures (повторный `setContent`, состояние без `remember`); исправлены вместе с импортами тестовых API.
- `./gradlew spotlessApply :app:testDebugUnitTest --tests '*GymDetailScreenTest' --tests '*GymsScreenTest'` — успешно после исправлений и расширения coverage.
- `./gradlew :app:testDebugUnitTest` — успешно: 955 тестов в 156 классах, 954 прошли, 1 пропущен, 0 failures/errors. Пропуск — условный `EmulatorV11CopyMigrationTest` для внешней копии БД.
- `./gradlew :app:assembleDebug` — успешно; APK: `app/build/outputs/apk/debug/app-debug.apk`.
- `./gradlew spotlessCheck` — успешно.
- Финальные полные тесты и сборка выполнены последовательно по одному разу на стабильном diff.

## Residual risks

- Открытых дефектов реализации нет. Визуальный осмотр снимков не выполнялся; UI проверен Compose-семантикой.
- Для UI кнопки повторной загрузки и отмены дочернего Flow нет отдельных изолированных тестов: повторная загрузка проверена на ViewModel, переключение источника использует `flatMapLatest` и прошло ревью; обновление доступности и исчезновение зала покрыты тестами.
- Коммит и push не выполнялись: пользователь их не запрашивал.

## Follow-up tasks: детали программ

| Task | Status | Owner | AC | Проверка |
|---|---|---|---|---|
| T-004 | done | Main | AC-008, AC-009, AC-013 | final full gate T-006 |
| T-005 | done | Routine implementer | AC-008, AC-010, AC-011, AC-012 | `:app:testDebugUnitTest --tests "*RoutineDetail*" --tests "*RoutineEditor*" --tests "*GymRoutesTest" --tests "*WorkoutsScreenTest"` |
| T-006 | done | Main/integrator + read-only tester/reviewer | AC-008…AC-013 | combined targeted; final `:app:testDebugUnitTest`; `:app:assembleDebug` |

| AC | Task | Test evidence |
|---|---|---|
| AC-008 | T-004, T-005 | `WorkoutsScreenTest` |
| AC-009 | T-004 | `CatalogOriginRowTest`, gym label tests |
| AC-010 | T-005 | `RoutineDetailViewModelTest`, `RoutineDetailScreenTest`, `GymRoutesTest`, `RoutineEditorScreenTest` |
| AC-011 | T-005 | `RoutineEditorViewModelTest`, `RoutineEditorScreenTest` |
| AC-012 | T-005 | routine detail tests, `WorkoutsScreenTest` |
| AC-013 | T-004 | final full gate T-006 |

## Follow-up findings and residual risks

- `observeRoutinesFull()` already returns routine, gyms, ordered exercises, planned sets and rest reactively; no new repository or DAO API is justified.
- `RoutineEditorViewModel` проверяет STANDARD/loading/saving перед всеми мутациями и перед/после асинхронного чтения выбранного упражнения. Прямой маршрут редактора стандартной программы показывает read-only body без полей, изменения залов, удаления, переноса или сохранения.
- Новый `RoutineDetailScreen` получает реактивный снимок Room; личная программа открывает редактор отдельно, все пункты «Просмотреть» ведут в detail. Начало тренировки и выбор программы в списке сохранены.
- Копирование использует единственный существующий `CatalogActionsViewModel` на границе экрана; `CatalogOriginContent` показывает busy/result, чистый renderer не зависит от Hilt.
- Targeted: новые routine VM/UI, direct-standard-editor, route, список и подписи прошли. Первоначально исправлена видимость общего `setsWord` для detail.
- Два прежних gym UI-теста зависели от каталога, оставленного другими тестами. Исправлены fixtures с явным publish/restore `LocalEquipmentCatalog`; отдельный `./gradlew spotlessApply :app:testDebugUnitTest --tests '*GymEditorScreenTest'` — успешно (5 тестов).
- Независимое ревью AC-008…013: pass, нет actionable P0/P1/P2. Coverage audit: pass. Пост-lookup guard проверен в коде; отдельный искусственный race-тест не добавлялся, поскольку загрузка блокирует начало такого lookup.
- `./gradlew :app:testDebugUnitTest` — успешно после follow-up: 963 теста в 158 классах, 962 прошли, 1 условный тест внешней копии БД пропущен, failures/errors = 0.
- `./gradlew :app:assembleDebug` — успешно; обновлён `app/build/outputs/apk/debug/app-debug.apk`.
- `./gradlew spotlessCheck` и `git diff --check` — успешно.
- Финальные unit-тесты и debug-сборка для follow-up выполнены последовательно по одному разу на стабильном diff. Версия осталась `29 / 1.3.21`: продолжение той же незакоммиченной фичи.
- AC-008…013 завершены. Новые снимки UI не запрашивались и не открывались; пользовательский пример использован как ориентир размера подписи. Коммит/push не выполнялись.

## Follow-up tasks: именованное клонирование

| Task | Status | Owner | AC | Проверка |
|---|---|---|---|---|
| T-007 | done | Main | AC-014, AC-015, AC-017, AC-018, AC-020 | `:app:testDebugUnitTest --tests "*GymRepositoryImplTest"` |
| T-008 | done | UI implementer | AC-015, AC-016, AC-019, AC-020 | `:app:testDebugUnitTest --tests "*ConfigurationClone*" --tests "*RoutineDetail*" --tests "*GymDetail*" --tests "*WorkoutsScreenTest"` |
| T-009 | done | Main/integrator + read-only review/coverage | AC-014…AC-020 | combined targeted; final `:app:testDebugUnitTest`; `:app:assembleDebug` |

| AC | Task | Test evidence |
|---|---|---|
| AC-014 | T-007 | existing routine UI tests; final full gate |
| AC-015 | T-007, T-008 | screen tests |
| AC-016 | T-008 | clone dialog tests |
| AC-017 | T-007 | `GymRepositoryImplTest`, routine VM tests |
| AC-018 | T-007 | `GymRepositoryImplTest` |
| AC-019 | T-008 | clone VM/dialog tests |
| AC-020 | T-007, T-008 | repository and UI tests |

## Named-clone findings and residual risks

- Frozen APIs retain source-only `duplicateRoutine` compatibility; existing fakes need no override.
- `cloneGym` copies configured and legacy links atomically, excludes routine refs, makes a PERSONAL row/new sync IDs and schedules only after commit.
- Main chose the same dialog for gym cloning after no user response; `GymsScreen` receives no new clone action.
- Gradle is deferred until T-007 and T-008 both stop.

- T-007 implementation ready: named routine clone preserves note, ordered sets/rest and gyms; gym clone preserves configured/legacy inventories. Added source immutability, blank/missing/conflict, rollback and post-commit scheduler regressions. Awaiting combined gate with T-008.

- Gate I: combined targeted ran 42 tests; all behavior tests passed. Fixed obsolete detail-body parameter, test import, and an OutlinedTextField assertion including its label; isolated dialog rerun passed.
- Independent review AC-014…020: pass, no actionable P0/P1/P2. Coverage audit initially requested a routine rollback regression and no-upload-on-failure assertion; both added, targeted `GymRepositoryImplTest` + `ConfigurationCloneViewModelTest` passed, coverage recheck pass.
- Final full unit suite started on stable code. No schema/migration, dependency, backend-format or additional version change.

- First final full run stalled in the test JVM (CPU active, results stopped advancing, no response to jcmd or SIGTERM); terminated only that test worker. No code changed. Repeated full command with `--info` for diagnosis succeeded in 41 seconds: 979 tests, 978 passed, 1 conditional external-DB-copy test skipped, 0 failures/errors, 160 classes. This environmental hang invalidated the first gate evidence and justified one repeat.

- Final `./gradlew :app:assembleDebug` passed (14s), `./gradlew spotlessCheck` and `git diff --check` passed. Updated APK: `app/build/outputs/apk/debug/app-debug.apk`.
- AC-014…020 completed. Version remains 29 / 1.3.21 for this uncommitted feature. Note is hidden in routine UI, stored note is preserved. No screenshots, commit or push performed. No open review or coverage findings.
