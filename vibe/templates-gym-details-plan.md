# План: шаблоны и карточка зала

Статус: AC-001…013 завершены и проверены. Slug: `templates-gym-details`.

## Цель и границы

Сделать стандартные программы и залы сворачиваемыми «Шаблонами» в начале списков и добавить карточку зала. Карточка показывает название, оснащение и реактивно доступные упражнения; редактирование личного зала остаётся отдельным действием.

Входит: списки программ/залов, навигация и detail-экран, защита редактора стандартного зала, сохранение UI-состояния и регрессии редактора. Не входят: Room/схема/миграции, `GymRepository`/backend/sync-контракты, зависимости, WorkManager, permissions и build/release-конфигурация. Версия уже поднята владельцем до `29 / 1.3.21`.

Допущения: стандартные записи уже помечены `origin == "STANDARD"`; копия идёт существующим `GymRoutes.gymEditor(copySourceGymId = id)` и сохраняется личной. Для сконфигурированного зала `gym.exercises` — legacy-снимок и не источник доступности.

## Acceptance criteria

| AC | Наблюдаемый результат | Проверка |
|---|---|---|
| AC-001 | Стандартные программы и залы находятся сверху в свёрнутом «Шаблоны»; личные идут ниже без суффикса происхождения. | screen/unit тесты T-002 |
| AC-002 | Раскрытие хранится `rememberSaveable`; выбранная программа не сбрасывается при сворачивании. | screen/unit тесты T-002 |
| AC-003 | Тап по любому залу ведёт в `gym_detail/{gymId}`; detail имеет M3 title, оснащение, загрузку/ошибку/отсутствие и reactive доступные упражнения из `observeAvailableExercises(setOf(id))`. | route + VM/screen тесты T-002 |
| AC-004 | Личный зал редактируется отдельной кнопкой; стандартный нельзя edit/delete, но можно явно скопировать существующим copy-flow. | screen/VM тесты T-001, T-002 |
| AC-005 | Прямо открытый стандартный editor, включая recreation, не меняет, не сохраняет и не удаляет запись; origin и legacy-флаг сохраняются и повторно валидируются. | `GymEditor*` тесты T-001 |
| AC-006 | Редактор сохраняет имя, поиск, группы, выбранные/все, bulk/undo/preview; одна кнопка сохранения, delete только personal overflow; discard/conflict/busy остаются. | `GymEditor*` тесты T-001 |
| AC-007 | Существующие DB-инварианты стандартных записей и legacy availability регрессируют без изменения DB-кода. | существующие targeted тесты T-001/T-002 и финальный набор T-003 |

## Поток и зафиксированные контракты

Текущий: Room → `GymRepository.observeGyms()`/`observeAvailableExercises()` → ViewModel `StateFlow` → список/редактор Compose. Список сейчас смешивает origin, тап стандартного зала запускает copy, а editor имеет неполные origin guards. Доступность настроенного зала уже вычисляет репозиторий по inventory requirements и очищает legacy `gym_exercises` после успешного сохранения inventory.

Целевой: `observeGyms` и Room-routines остаются SSOT. Списки разделяют уже полученный immutable список на standard/personal; `rememberSaveable` хранит только expanded, ViewModel продолжает владеть selected routine. `GymDetailViewModel` владеет `gymId` из `SavedStateHandle`, комбинирует наблюдаемый зал с `flatMapLatest { observeAvailableExercises(setOf(id)) }`, отменяет старый дочерний Flow при смене id и переводит исключения в immutable state. UI посылает события (open/detail/edit/copy/retry/back), не обращается к DAO. Нет новых Hilt binding/scope: Hilt создаёт новый VM, существующий singleton repository остаётся владельцем I/O. Нет фоновой работы, разрешений или транзакций.

API-контракт навигации: добавить `GYM_DETAIL = "gym_detail/{gymId}"` и `fun gymDetail(id) = "gym_detail/${Uri.encode(id)}"`; destination получает String argument и делает обычный pushed horizontal `GymMotion` transition. Любой card tap → detail. В detail personal action → `gymEditor(id)`, standard action «Создать личную копию» → `gymEditor(copySourceGymId = id)`; edit/delete standard отсутствуют. `GymsScreen` и `GymDetailScreen` получают `windowWidthClass: GymWindowWidthClass = Compact`, а единственный nav-writer передаёт class из `GymNavGraph`.

## Задачи

| ID | Owner / файлы | Зависит | Действия | Автопроверка | Done / AC |
|---|---|---|---|---|---|
| T-001 | **Editor writer** — `ui/gyms/GymEditorScreen.kt`, `GymEditorViewModel.kt`; `ui/GymEditorViewModelTest.kt`, `ui/GymEditorScreenTest.kt`, `ui/ExpandedGymEditorScreenTest.kt` | frozen contracts | Закрыть все mutating entry points (`set*`, toggle, bulk/undo, preview, save/delete) для STANDARD и busy; при `DRAFT_LOADED` восстановить и повторно сверить origin/legacy snapshot, не дать stale saved state обойти guard. Сохранить personal UX AC-006 и overlay delete только personal. | После wave: `./gradlew :app:testDebugUnitTest --tests "*GymEditorViewModelTest" --tests "*GymEditorScreenTest" --tests "*ExpandedGymEditorScreenTest"` | Все попытки изменить/сохранить/удалить standard остаются no-op, включая restored draft; personal сценарии проходят. AC-004–007 |
| T-002 | **List/detail/nav writer** — `ui/workouts/WorkoutsScreen.kt`, `ui/gyms/GymsScreen.kt`, при нужде `GymsViewModel.kt`, новый `ui/gyms/GymDetailScreen.kt`, `GymDetailViewModel.kt`, `ui/navigation/GymNavGraph.kt`; `ui/navigation/GymRoutesTest.kt`, новые `ui/GymDetailViewModelTest.kt`, `ui/GymDetailScreenTest.kt`, `ui/WorkoutsScreenTest.kt`, `ui/GymsScreenTest.kt` (и `TemplatesSectionHeader.kt`/test только если вынесен общий компонент) | frozen contracts | Разделить standard/personal, добавить accessible expandable header и сохранить selection. Поменять tap gym на detail, добавить nav route/destination и detail states/actions; применять `observeAvailableExercises(setOf(id))`, не `gym.exercises`. Передать adaptive class с Compact default. | После wave: `./gradlew :app:testDebugUnitTest --tests "*GymRoutesTest" --tests "*GymDetailViewModelTest" --tests "*GymDetailScreenTest" --tests "*WorkoutsScreenTest" --tests "*GymsScreenTest"` | Маршрут кодирует id; все UI состояния и origin actions доступны и корректны; lists/detail удовлетворяют AC-001–004,007. |
| T-003 | **Main/integrator** — `data/GymRepositoryImplTest.kt`, `vibe/templates-gym-details-plan-track.md` | T-001, T-002 | Добавить SQLite-boundary регрессию: `saveGymInventory`/`deleteGym` возвращают `Failure` для standard и не меняют rows. После остановки writers проверить diff/traceability, выполнить один общий targeted gate; затем единожды final project gates и записать результаты. Исправления возвращать владельцу slice. | targeted `./gradlew :app:testDebugUnitTest --tests "*GymRepositoryImplTest" --tests "*GymEditor*" --tests "*GymDetail*" --tests "*GymRoutesTest" --tests "*Workouts*"`; final `./gradlew :app:testDebugUnitTest`, затем `./gradlew :app:assembleDebug` | SQLite protection и все AC имеют evidence, нет P0/P1. AC-001–007 |

## Ownership и waves

| Область | Единственный владелец |
|---|---|
| Editor, его state/events и три существующих editor-теста | Editor writer (T-001) |
| Списки, detail, routes/nav, adaptive handoff, list/detail/navigation tests и возможный shared header | List/detail/nav writer (T-002) |
| Версия, дизайн-документация, `GymRepositoryImplTest.kt`, запуск Gradle, интеграция tracker | Main/integrator (T-003) |

Wave 0: contracts выше заморожены. Wave 1: T-001 и T-002 параллельно, без Gradle. Wave 2: T-003 sequentially; при finding только исходный owner правит свой slice. Один implementation writer допустим как fallback; параллельность разрешена только с этой таблицей.

## Quality gates, риски и rollback

Применимы: StateFlow/`WhileSubscribed(5000)`, immutable state/events, lifecycle collection, cancellation `flatMapLatest`, navigation/back/restoration, loading/empty/error/content, adaptive compact/medium/expanded, TalkBack expanded state/action, font scale 2.0, 48dp targets, M3/GymCard/SectionCard, `GymMotion` и `GymHaptics`. Не применимы: Room migration/schema, DI binding, new dependency/R8/release, WorkManager/service, manifest/permission. Финальные gates — T-003; release assemble не нужен (версия — уже существующая только build edit и feature не меняет release-sensitive config).

Риск: stale `SavedStateHandle` может превратить standard edit в personal или обойти guard; T-001 сверяет origin с repository до mutating operations. Риск: detail ошибочно использует legacy links; T-002 проверяет configured gym с очищенными `gym.exercises`. Rollback удаляет только UI/nav code; сохранённые rows и data contracts не меняются. Открытых product/blocker-вопросов нет.

## Gate P self-check

Все AC-001…007 сопоставлены с T-001/002/003 и командами; ownership не пересекается; маршруты, source-of-truth, copy-flow, restoration и adaptive contract заморожены; перечислены только применимые conditional gates. Рекомендуемая первая реализация: T-001, чтобы закрыть mutation boundary стандартного зала до открытия detail и новых путей навигации.

## Follow-up: детали программ

Объём: отдельный read-only экран программы, перевод всех просмотров программ на него и defensive read-only standard editor. Версия остаётся `29 / 1.3.21`: это та же незакоммиченная feature, повторного инкремента нет. Не меняются Room/schema, DAO/API contracts, Hilt, зависимости, backend/sync, permissions и background work.

| AC | Наблюдаемый результат | Task / проверка |
|---|---|---|
| AC-008 | Списки называют верхнюю секцию «Встроенные», её раскрытие восстанавливается, сворачивание сохраняет выбранную программу. | T-004 / `WorkoutsScreenTest` |
| AC-009 | Общий titleMedium label «Стандартное» показывается там, где нужен origin. | T-004 / `CatalogOriginRowTest`, gym tests |
| AC-010 | Любой «Просмотреть» открывает `routine_detail/{routineId}`. Detail показывает gyms, exercises, sets/rest без input/select/remove/drag/save; у personal отдельный Edit, у standard его нет. | T-005 / route, detail VM/screen и editor tests |
| AC-011 | Прямой standard routine editor, в том числе restored/async library result, display-only: все VM mutators и loading guards не меняют/не сохраняют. | T-005 / `RoutineEditorViewModelTest`, `RoutineEditorScreenTest` |
| AC-012 | Копирование не меняется; detail реактивно обрабатывает loading/missing/error и fontScale 2.0. | T-005 / detail tests |
| AC-013 | Карточка «Залы» в Settings сразу после «Тренировка». | T-004 / final full gate T-006 |

Контракт: `RoutineDetailViewModel` получает `routineId` из `SavedStateHandle` и читает только `RoutineDao.observeRoutinesFull()` → выбрать matching `RoutineWithExercises` → immutable `StateFlow` через `stateIn(WhileSubscribed(5000))`; Flow — SSOT для gyms, ordered exercises, `plannedSetsJson` и rest. Ошибку переводит в UI state, отсутствующая запись — missing; UI не вызывает DAO. Новый route `routine_detail/{routineId}` — обычный pushed `GymMotion` экран; info упражнения ведёт в существующий `exercise_detail/{id}`. Меню всех origins вызывает detail; duplicate остаётся существующим `WorkoutsViewModel.duplicate`/`CatalogOriginRow` flow. Новых DI bindings/scopes, dispatcher, cancellation owner или persistent state нет.

| ID | Owner / exact files | Depends | Actions and done condition | Automated verification / AC |
|---|---|---|---|---|
| T-004 | **Main — done** — `ui/components/TemplatesSectionHeader.kt`, `CatalogOriginRow.kt`, `ui/settings/SettingsScreen.kt`, gym label tests, `docs/*`, `app/build.gradle.kts` | frozen contract | «Встроенные» с visible semantics, titleMedium origin label, order Settings и docs выполнены; version не менялась. | Final full gate T-006. AC-008,009,013 |
| T-005 | **Routine implementer** — only changed/new `ui/routine/*`, `ui/workouts/WorkoutsScreen.kt`, `ui/navigation/GymNavGraph.kt`, `RoutineEditorViewModelTest.kt`, `RoutineEditorScreenTest.kt`, new routine-detail tests, `ui/navigation/GymRoutesTest.kt`, `ui/workouts/WorkoutsScreenTest.kt` | frozen contracts | Добавить route/detail VM+screen and navigate all views there; preserve card select/start and duplicate. Make standard editor’s UI and every mutator (`set*`, gyms, exercise add/remove/reorder, set edits, save; async picker result) no-op/read-only, including loading. Detail has reactive loading/error/missing/content, personal Edit and standard no Edit. | After writer stops: `./gradlew :app:testDebugUnitTest --tests "*RoutineDetail*" --tests "*RoutineEditor*" --tests "*GymRoutesTest" --tests "*WorkoutsScreenTest"`. AC-008,010–012 |
| T-006 | **Main/integrator + read-only tester/reviewer** — tracker only | T-004,T-005 | No Gradle while writers. Main runs one combined targeted command; then tester/reviewer inspect stable diff read-only. Route fixes to T-005 once; final full unit then debug assembly exactly once and record evidence. | targeted `./gradlew :app:testDebugUnitTest --tests "*RoutineDetail*" --tests "*RoutineEditor*" --tests "*WorkoutsScreenTest" --tests "*GymRoutesTest"`; final `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug`. AC-008–013 |

Follow-up ownership: main exclusively owns shared header/origin label/settings/docs/version; routine implementer exclusively owns listed routine/list/nav files and tests. T-004 is complete; Wave 1 now is T-005 alone with no Gradle. Wave 2: T-006 sequential integration and read-only review after the targeted gate. Relevant quality gates: immutable StateFlow, lifecycle/read-only event boundary, `WhileSubscribed`, back/navigation/restoration, loading/error/missing/content, TalkBack/48dp/fontScale 2, M3/GymMotion/haptics and compact/medium/expanded. Room/migration, background, permissions, new DI/dependency and release gates are not applicable. Risk: stale async library return mutates standard editor; test it after lookup completion. Rollback is UI/nav-only and preserves database rows.

### Gate P follow-up self-check

AC-008…013 each map to a task and command; T-004/T-005 file sets do not overlap; routine detail’s read-only DAO/route contract and standard-editor guard boundary are frozen. Recommended first routine implementation task: T-005 after T-004 starts.

## Follow-up: именованное клонирование

Сохраняются завершённые evidence. Версия остаётся `29 / 1.3.21`; schema/wire/dependencies/public external contracts не меняются. Main уже удалил UI заметки программы и переименовал gym labels/tests.

| AC | Результат | Task / evidence |
|---|---|---|
| AC-014 | В routine detail нет UI заметки; сохранённая note остаётся в данных. | T-007 / existing UI tests + final gate |
| AC-015 | Меню routine всех origins: «Открыть» и «Клонировать»; gym clone label согласован. | T-007,T-008 / screen tests |
| AC-016 | Любой clone-path открывает единый name dialog с prefill `<source> (копия)`, Cancel/Save. | T-008 / clone dialog tests |
| AC-017 | Routine clone атомарен, создаёт personal copy и scheduler запускается только после успеха. | T-007 / `GymRepositoryImplTest`, VM tests |
| AC-018 | Gym clone атомарно переносит inventory, `gym_equipment` и legacy `gym_exercises`, но не routine refs. | T-007 / `GymRepositoryImplTest` |
| AC-019 | Trim/nonblank, single-flight Save, failure/error и запрет dismissal во время сохранения корректны. | T-008 / clone VM/dialog tests |
| AC-020 | Новые personal sync IDs, source неизменён; успех виден существующему reactive списку. | T-007,T-008 / repository and UI tests |

Frozen APIs: `GymRepository.duplicateRoutine(sourceRoutineId: Long, name: String? = null): RoutineEntity?` и `cloneGym(sourceGymId: String, name: String): SaveGymResult` (default `Failure`). Старый source-only caller совместим. `cloneGym` создаёт PERSONAL gym/new syncId, копирует inventory and links в одной transaction, без routine refs; после commit repository schedules upload. Routine VM schedules `RoutineUploadScheduler` только после успешного named clone. Диалог владеет immutable draft/busy/error и one-shot success: Cancel не пишет, ошибка сохраняет draft, успех приходит через Room flows. SSOT — repository; Hilt scopes/dispatchers unchanged.

| ID | Owner / files | Depends | Action and done condition | Verification / AC |
|---|---|---|---|---|
| T-007 | **Main** — `domain/GymRepository.kt`, `data/GymRepositoryImpl.kt`, `data/GymRepositoryImplTest.kt`, docs/tracker; completed small routine note/gym-label UI | frozen APIs | Named atomic routine/gym clone plus SQLite regressions; preserve source, legacy links and no routine refs. | `./gradlew :app:testDebugUnitTest --tests "*GymRepositoryImplTest"`; AC-014,015,017,018,020 |
| T-008 | **UI implementer** — new `ConfigurationCloneViewModel`/Dialog and tests; `ui/workouts/WorkoutsScreen.kt`, `ui/routine/RoutineDetailScreen.kt`, `ui/gyms/GymDetailScreen.kt`, integration tests/`GymNavGraph.kt` only if required | frozen APIs | Replace routine `CatalogActionsViewModel` use with shared named clone; route existing routine/detail and gym-detail clone actions into dialog; no `GymsScreen` action. Preserve main-card select/start. | after writers: `./gradlew :app:testDebugUnitTest --tests "*ConfigurationClone*" --tests "*RoutineDetail*" --tests "*GymDetail*" --tests "*WorkoutsScreenTest"`; AC-015,016,019,020 |
| T-009 | **Main/integrator + read-only review/coverage** — tracker only | T-007,T-008 | No Gradle while writers. Combined targeted, read-only review/coverage, one fix routing, final unit and debug once. | targeted repository+UI; `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug`. AC-014…020 |

Main exclusively owns repository contracts/implementation/tests/docs; UI writer exclusively owns dialog and named screens/nav tests. Relevant gates: Room transaction/data preservation, immutable VM state/events, cancellation/single-flight, loading/error/recreation, navigation/back, 48dp/TalkBack/fontScale 2. No migration, WorkManager, permission, service, dependency or release gate applies. Risk: legacy gym loses links or failed dialog double-writes; T-007 transaction tests and T-008 one-flight/error tests are mandatory. Rollback removes UI entry points and preserves source/data.

### Gate P named-clone self-check

AC-014…020 map to T-007…009 and automated evidence; APIs, transaction/scheduler ownership and file sets are frozen. T-008 can start against the frozen API while T-007 completes; no Gradle until both stop.
