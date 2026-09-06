# План: оборудование залов и упражнений

## Статус и цель

**Результат:** реализовано; final unit/debug и независимое review PASS. Пользовательское расхождение routine editor/picker исправлено общим reactive availability. Release blocked только отсутствием подписи; подробности в `gym-equipment-plan-track.md`.

**Статус:** 2026-09-07 владелец подтвердил Q-001..Q-003 и предложенную разметку
неоднозначных названий, разрешил реализацию. Только встроенный каталог; legacy-ограничения
`gym_exercises` действуют до первого успешного сохранения оснащения. Старые свои упражнения
с неизвестными требованиями не считаются «без оборудования» и исключены из configured-залов
до явной разметки; конфликт с программой/активной тренировкой блокирует переход атомарно.
Повторный импорт legacy-формата не откатывает уже настроенное оснащение.

**Цель:** хранить инвентарь каждого зала и явные требования упражнения, вычислять
доступность по полному покрытию требований, не меняя историю, ID или результаты тренировок.

### Scope

- встроенный типизированный каталог оборудования, его 263 проверенные разметки и capability
  покрытия (включая совместимость скамей);
- Room v13 → v14, repository-инварианты, Sheets export/import и локальный DB export;
- редактор/копирование зала, требования упражнения, библиотечный OR-фасет и detail-отображение;
- конфликты программ и активной тренировки без частичной записи или автоматической замены.

### Non-goals

Альтернативные наборы для одного упражнения, варианты исполнения, учёт экземпляров/рукоятей/
весов, пресеты, статистика оборудования и автоматическая замена упражнения. Custom CRUD
оборудования исключён по подтверждённому Q-001.

### Assumptions

- один модуль `:app`; Room — SSOT, UI не обращается к DAO;
- текущее значение версии перед фичей: `versionCode=21`, `versionName=1.3.13`; единственный
  финальный writer поднимает до `22` / `1.3.14` после сверки с целевой веткой;
- нет нового permission, service, Worker или dependency; существующий configuration upload
  остаётся дефerrable и должен быть идемпотентен;
- UI следует `docs/design-system.md`: M3/`GymMotion`/`GymHaptics`, цвета только theme, строки Kotlin.

## Acceptance criteria

| AC | Наблюдаемое условие |
|---|---|
| AC-001 | Новый зал пуст; гантели и скамья сохраняются и повторно открываются как оборудование. |
| AC-002 | «Выбрать всё» охватывает полный текущий пул, в том числе скрытые группы и бассейн. |
| AC-003 | Поисковые bulk-действия меняют только текущие результаты/режим. |
| AC-004 | Группа имеет none/partial/all; partial→all, all→none в своей области. |
| AC-005 | Undo bulk до следующей правки возвращает снимок; тап в All не меняет порядок/раскрытие. |
| AC-006 | Search, All/Selected, preview и back сохраняют draft; discard/error не меняют persisted state. |
| AC-007 | Copy создаёт независимый несохранённый зал; cancel не создаёт строк. |
| AC-008 | Empty requirements доступны без оборудования; pull-up/pool/dumbbell bench press требуют верные позиции. |
| AC-009 | Adjustable flat+incline покрывает flat/incline, но не decline. |
| AC-010 | Несколько залов дают пересечение покрытых требований; без залов — полный каталог. |
| AC-011 | Equipment facets OR; поиск/прочие facets/gym restriction остаются AND; reset не снимает gyms. |
| AC-012 | Добавление инвентаря открывает покрытые упражнения; будущие позиции не auto-add. |
| AC-013 | Все 263 built-ins имеют ручную проверенную canonical-разметку, без generic machine. |
| AC-014 | Выбор упражнения в тренировке не просит оборудование; создание/правка exercise не меняет gym. |
| AC-015 | Конфликт показывает упражнения и недостающий inventory, не удаляет/частично не пишет routine/workout. |
| AC-016 | Offline работает; Sheets round-trip хранит inventory/identity без дублей при retry/import. |
| AC-017 | История, exercise IDs и результаты неизменны; barbell/dumbbell движения остаются разными IDs. |
| AC-018 | При fontScale 2 и TalkBack доступны search, state/action single/group/bulk и save. |

## Current → target flow

```text
Current: GymEditor → GymRepository.saveGym(exercise IDs) → gyms + gym_exercises
         → availability/intersection SQL → routine / active-workout conflict
         → GymSheetRows → ConfigurationSheetsRepository → Sheets/import

Target:  EquipmentCatalog + ExerciseRequirements (immutable)
         GymEditor draft equipment IDs → GymRepository.saveGymInventory
         → Room transaction: gyms + gym_equipment; legacy conversion only after explicit save
         → requirement-capability coverage → availability/intersection + conflict details
         → GymSheetRows/ExerciseSheetRows v2 → Sheets/import transaction → Room SSOT → Flow → VM → Compose
```

Room owns persisted gyms, requirements and migration state. `GymRepository` owns availability,
save and conflict transactions. `ExerciseCatalogRepository` owns catalog projection. ViewModels own
only immutable UI state and event Channels; `SavedStateHandle` holds only route id, name/query,
mode/group expansion and selected equipment IDs necessary to recreate an unsaved draft. The
repository uses caller cancellation; no `GlobalScope`, detached writer or new background work.

## Согласованные контракты (T-001, 2026-09-07)

Поведение зафиксировано. Общие API зафиксированы перед передачей непересекающегося библиотечного среза второму исполнителю; Room/схема/миграции остаются у одного владельца.

- `EquipmentId` is a stable built-in Kotlin ID; `EquipmentCatalog` is deterministic, grouped,
  searchable by agreed synonyms, and adding a catalog item never mutates a saved gym.
- `ExerciseEquipmentRequirement(exerciseId, equipmentId)` is a required capability; `empty` is
  explicitly **no equipment**; absence/unknown legacy requirement is a third state, never empty.
- `GymEquipmentEntity(gymId, equipmentId)` is the persisted selected inventory. Capability expansion
  (adjustable bench → flat+incline) lives in the canonical catalog/projector, not duplicated in SQL.
- `GymConfiguration` exposes `equipmentIds` and requirement state, replacing UI use of exercise lists.
  `GymAvailability`/`GymConfigurationConflict` return affected exercises plus missing equipment IDs.
- A configured gym is available iff every known requirement is covered. Multiple gyms each must
  cover it. A gym without inventory is not a full catalog; an unbound routine is the full catalog.
- Every inventory save validates all affected routines and active workout then writes/rolls back in
  one `withTransaction`. Exercise create/update never inserts `gym_equipment`.
- Legacy `gym_exercises` remains readable only under Q-002's legacy state. A validated inventory
  save atomically marks that gym configured and removes/retires its legacy exercise links. A legacy
  reimport never overwrites configured inventory. New snapshot conflict resolution is `updatedAt`
  plus tombstones, validated in one transaction.
- `SheetSyncModels`/parsers version and preserve the three requirement meanings: unknown legacy,
  explicit empty, specified IDs. Legacy rows cannot downgrade configured inventory; invalid or
  unknown references reject the whole import transaction. Stable sync IDs, exercise IDs, historical
  workout rows and existing routine/workout rows are immutable.

**Canonical meanings confirmed by owner 2026-09-07:** Turkish/goblet=kettlebell;
Romanian/sumo=barbell; seated calf=dedicated machine; hyper/reverse=dedicated apparatus; T-bar=
dedicated T-bar; Arnold/concentration=dumbbells+bench; lying French=EZ+flat bench; wrist flex/ext=
barbell+bench; farmer/suitcase/overhead=dumbbells; weighted plank/push-up=plate; barbell presses=
bench+rack (board additionally board); deficit=platform; block pull=blocks.

## Tasks

| Task | Owner / deps | Exact files | Action | Automated verification / observable done | AC |
|---|---|---|---|---|---|
| T-001 | Product owner; none | `vibe/gym-equipment-plan.md`, `vibe/gym-equipment-plan-track.md` | Decide Q-001..Q-003 and approve canonical meanings; update frozen-contract section before implementation. | Signed decision recorded; all dependent rows unblocked. | AC-001..018 |
| T-002 | Writer; T-001,T-009A | `app/src/main/java/com/valerochka1337/valerochkagym/data/db/EquipmentCatalog.kt` (new), `app/src/main/java/com/valerochka1337/valerochkagym/data/db/CanonicalExerciseRegistry.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/domain/ExerciseCatalogRepository.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/domain/ExerciseCatalogProjector.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/data/ExerciseCatalogRepositoryImpl.kt`, `vibe/gym-equipment-mapping.md` (generated), `app/src/test/java/com/valerochka1337/valerochkagym/data/db/EquipmentCatalogTest.kt` (new), `app/src/test/java/com/valerochka1337/valerochkagym/domain/ExerciseCatalogProjectionTest.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/data/ExerciseCatalogRepositoryImplTest.kt` | Add stable built-in taxonomy, capabilities/synonyms and full 263 explicit mappings; generate reviewable ID/name/requirements report; an independent reviewer semantically inspects every row, escalating only genuinely ambiguous history meanings to owner. Project equipment OR facet while preserving existing AND constraints. | Tests prove 263 coverage/no generic machine/AC-008/009/011; reviewer records all-row semantic verdict in mapping report. | 008,009,011,013,014,017 |
| T-003 | Writer; T-001,T-002 | `app/src/main/java/com/valerochka1337/valerochkagym/data/db/entity/GymEquipmentEntity.kt` (new), `app/src/main/java/com/valerochka1337/valerochkagym/data/db/entity/ExerciseEquipmentEntity.kt` (new), `app/src/main/java/com/valerochka1337/valerochkagym/data/db/entity/GymEntity.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/data/db/entity/ExerciseEntity.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/data/db/dao/GymDao.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/data/db/dao/ExerciseDao.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/data/db/GymDatabase.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/data/db/GymDatabaseCallback.kt`, `app/schemas/com.valerochka1337.valerochkagym.data.db.GymDatabase/14.json`, `app/src/test/java/com/valerochka1337/valerochkagym/data/GymDaoTest.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/data/db/Migration13To14Test.kt` (new) | Own v14 entities/indices/FKs, migration and schema. Preserve `gym_exercises`; add explicit legacy/configured discriminator and requirement-state representation on `ExerciseEntity`. Register only handwritten migration. | Robolectric incremental 13→14 and supported full path preserve rows; newest exported schema committed; `fallbackToDestructiveMigration` absent. | 001,008,012,013,017 |
| T-004 | Writer; T-002,T-003 | `app/src/main/java/com/valerochka1337/valerochkagym/domain/GymRepository.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/data/GymRepositoryImpl.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/data/ActiveWorkoutRepositoryImpl.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/data/db/dao/GymDao.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/data/db/relation/GymWithExercises.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/data/GymRepositoryImplTest.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/ui/GymEditorViewModelTest.kt` | Replace manual exercise-list save/read with inventory and coverage; calculate multi-gym availability and missing requirements. One transaction validates both inventory saves and custom-exercise requirement edits against every affected routine/active workout; conflict returns missing equipment, preserves original requirements and all rows; copy creates no row until save. | Targeted tests cover rollback/conflict/missing IDs, a requirement edit invalidating a known configured gym, no-gym full catalog, legacy/configured/custom-unknown branches; no partial persisted change. | 001,007,008,010,012,014,015,017 |
| T-005 | Writer; T-002,T-003,T-004 | `app/src/main/java/com/valerochka1337/valerochkagym/domain/GymSheetRows.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/domain/ExerciseSheetRows.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/domain/SheetSyncModels.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/data/google/ConfigurationSheetsRepository.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/data/google/WorkoutImportRepository.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/domain/GymSheetRowsTest.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/domain/ExerciseSheetRowsTest.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/data/ConfigurationSheetsRepositoryTest.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/data/ConfigurationImportRepositoryTest.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/data/WorkoutImportRepositoryTest.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/data/DatabaseExporterTest.kt` | Version sheet rows/models/parsers; freeze unknown-vs-empty-vs-specified semantics, inventory/requirement rows and tombstones. Reject unknown/invalid refs with full rollback; apply updated snapshot/tombstone and legacy anti-downgrade in one DB transaction. No production edit to `DatabaseExporter`: verify it copies v14 tables/rows. | Targeted round-trip/idempotence/stale snapshot/invalid-reference rollback/legacy reimport tests; DatabaseExporterTest proves v14 data export. | 016,017 |
| T-006 | Writer; T-002,T-004 | `app/src/main/java/com/valerochka1337/valerochkagym/ui/gyms/GymEditorViewModel.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/ui/gyms/GymEditorScreen.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/ui/gyms/GymsViewModel.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/ui/gyms/GymsScreen.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/ui/navigation/GymNavGraph.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/ui/GymEditorViewModelTest.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/ui/GymEditorScreenTest.kt` (new), `app/src/test/java/com/valerochka1337/valerochkagym/ui/AdaptiveNavigationTest.kt` | Implement immutable editor state/events: grouped tri-state selection, scoped bulk/undo invalidation, All/Selected/search, preview, save/discard/continue, errors and copy route. Use stable keys, semantics, GymMotion/Haptics, insets and adaptive policy. | VM tests cover AC-001..007/015; Compose semantics tests verify labels/state/actions, 48dp and fontScale=2 compact+expanded; navigation recreation retains minimal draft. | 001..007,012,015,018 |
| T-007 | Writer; T-002,T-004 | `app/src/main/java/com/valerochka1337/valerochkagym/ui/library/ExerciseLibraryViewModel.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/ui/library/ExerciseLibraryScreen.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/ui/library/ExerciseEditorSheet.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/ui/exercise/ExerciseDetailViewModel.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/ui/exercise/ExerciseDetailScreen.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/ui/ExerciseLibraryViewModelTest.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/ui/library/ExerciseLibraryScreenTest.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/ui/library/ExerciseEditorSheetTest.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/ui/exercise/ExerciseDetailScreenTest.kt` | Add equipment facet, explicit no-equipment editor state, requirement display and unchanged gym-independent exercise editing. Include loading/empty/error/content and zero-result reset action. | Targeted VM/Compose tests prove facet algebra, no automatic gym add and semantic/accessibility states. | 008,011,014,018 |
| T-008 | Writer (shared choke points); T-003..T-007 | `app/src/main/java/com/valerochka1337/valerochkagym/di/DataModule.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/di/DomainModule.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/ui/navigation/GymNavGraph.kt` | Bind repositories/catalog at application scope and wire routes; no new dependency, permission, Worker or service. | Targeted compile/test owned by tester passes. | 001,010,014,016,017 |
| T-009A | Root: strict plan reviewer; T-001 | `vibe/gym-equipment-plan-track.md` | Strict reviewer validates the resolved plan/contracts before T-002 starts; root routes findings once. | No open P0/P1 plan finding; verdict recorded. | 001..018 |
| T-009B | Root: implementer, then tester + reviewer; T-002..T-008 | `vibe/gym-equipment-plan-track.md` | Implementer runs targeted boundary checks. After stable diff, tester runs only missing/gap-targeted checks while reviewer audits ACs/diff in parallel; root routes findings. | No open P0/P1 finding; commands/verdicts recorded without repeating already-passed targeted tests absent a gap. | 001..018 |
| T-010 | Root; T-009B | `app/build.gradle.kts`, `ARCHITECTURE.md`, `vibe/gym-equipment-plan-track.md` | Compare target branch, make the one version bump, update architecture integration, then run final gates sequentially once. | `./gradlew :app:testDebugUnitTest`; then `./gradlew :app:assembleDebug`; then `./gradlew :app:assembleRelease` or signing blocker. | 001..018 |

All listed paths are exact. No Gradle runs while parallel writers work.

## Ownership and waves

**Актуальное распределение после фиксации API (2026-09-07):**

| Boundary | Sole writer | Files |
|---|---|---|
| Room/schema/availability/sync | equipment_implementation | T-003..T-005, EquipmentCatalog, GymRepository/Impl, DI; все миграции и DAO у него |
| Gym UI/navigation, library/editor/detail and catalog projection | equipment_library_implementation | T-006..T-007; ExerciseCatalogRepository/Projector and related models, ExerciseCatalogRepositoryImpl, corresponding tests |
| Explicit canonical mapping | Root | CanonicalEquipmentRequirements.kt, vibe/gym-equipment-mapping.md |
| Integration/gates | Root | app/build.gradle.kts, ARCHITECTURE.md, plan/tracker, финальные Gradle-проверки |

Frozen shared API: `NewExerciseConfiguration.requirements: ExerciseEquipmentRequirements?`
(null сохраняет старое значение); `GymRepository.saveExerciseConfiguration(configuration,
gymIds, workoutId?)` returns `SaveExerciseConfigurationResult.Saved(exercise)` /
`Conflict(GymConfigurationConflict)` / `Failure`. `requirementsFor(exercise)` returns
UnknownLegacy / ExplicitNone / Required(equipmentIds). `GymConfiguration` carries
`equipmentIds` and `inventoryConfigured`. Inventory editing uses `saveGymInventory`.
Library writer does not edit shared repository/entity APIs; required adjustments go to their owner.

1. T-001 и T-009A завершены до кода.
2. После фиксации перечисленных API два исполнителя работают только в своих файлах.
3. Пока оба пишут, Gradle запрещён; root запускает общий targeted gate после обоих результатов.
4. После стабильного diff — tester/reviewer параллельно, затем root T-010 final gates.

## Quality gates

- **Room:** handwritten v13→14 and full-path migration; latest schema checked in; transactional
  save/import and migration regression tests. No destructive fallback.
- **Flow/cancellation:** `StateFlow` + `stateIn(WhileSubscribed(5000))`, live collection in VM
  tests; injected compute dispatcher only for materially heavy projection; save job is cancelled by
  ViewModel clear and no detached work.
- **UI:** loading/empty/error/content, save failure and conflict; restore minimal draft; compact and
  medium/expanded; fontScale 2, TalkBack state/action labels, 48dp, non-colour cues, stable keys.
  No screenshot inspection is planned.
- **Sync/background:** existing configuration upload is idempotent under retry; unique scheduling,
  stale timestamp/tombstone and invalid-row behavior tested. No new permission/Worker/service.
- **Release:** because persistence/schema serialization changes, `:app:assembleRelease` after the
  final standard pair when signing inputs permit it. Final pair exactly once, sequential: full unit
  suite then debug assembly.

## Risks, blockers and rollback

| ID | Question / risk | Safe branch / required answer |
|---|---|---|
| Q-001 | Built-ins only or custom equipment CRUD? | Confirmed: built-ins only; custom equipment CRUD is excluded. |
| Q-002 | Legacy gym restriction semantics before an inventory save? | Confirmed: retain `gym_exercises` until explicit validated save; legacy import never downgrades configured inventory. |
| Q-003 | Unknown requirements on old custom exercises and exact 263 mapping? | Confirmed: unknown remains distinct and excluded from configured gyms until explicitly mapped; canonical meanings above accepted. |
| R-001 | A faulty mapping exposes unusable exercise. | Canonical exhaustive test plus owner scenario pass; no generic-machine fallback. |
| R-002 | Import overwrites configured inventory. | Separate legacy/configured state, timestamps+tombstones, transaction and regression tests. |
| R-003 | Conflict save corrupts program/active workout. | Validate complete candidate first; one transaction; no auto deletion/replacement. |
| R-004 | Rollback/data preservation. | Migration only adds data/state; preserve `gym_exercises`, history, IDs and Sheets source rows. A rollback app must understand v14 or export database first; never destructive downgrade. |

## Gate P self-check

AC-001..018 each map to T-002..T-010 and a targeted or final verification above; Room ownership is
single-writer; navigation/DI/version have a named choke-point owner; relevant conditional gates are
listed. Q-001..Q-003 confirmed on 2026-09-07. T-009A rechecks only the corrected plan
findings and decision record before T-002 starts. No unresolved product questions remain.
