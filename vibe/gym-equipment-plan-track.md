# Трекер: оборудование залов и упражнений

**Статус плана:** Q-001..Q-003 подтверждены владельцем 2026-09-07; реализация разрешена. Реализация завершена, включая пользовательский regression routine editor. Gate I/T/V и final unit/debug PASS; release ограничен отсутствием локальной подписи.

## Tasks

| Task | Status | Owner | Depends on | Done condition |
|---|---|---|---|---|
| T-001 | complete | Product owner | — | Q-001..Q-003 и canonical meanings записаны в плане. |
| T-002 | complete | Writer | T-001,T-009A | Stable catalog + all 263 mappings, generated report and semantic row review pass. |
| T-003 | complete | Writer | T-001,T-002 | v14 migration/schema/full-path tests pass and retain legacy rows. |
| T-004 | complete | Writer | T-002,T-003 | Atomic availability/save/conflict behavior passes repository tests. |
| T-005 | complete | Writer | T-002,T-003,T-004 | Sheets parser states are frozen; import is idempotent, anti-downgrade and rollback-safe. |
| T-006 | complete | Writer | T-002,T-004 | Gym UI supports draft, bulk, undo, copy, conflict and accessible adaptive states. |
| T-007 | complete | Writer | T-002,T-004 | Library/editor/detail expose requirements and facet semantics. |
| T-008 | complete | Writer | T-003..T-007 | DI and navigation integration compiles through tester-owned targeted gate. |
| T-009A | complete | Root/strict reviewer | T-001 | Pre-implementation plan review verdict is recorded. |
| T-009B | complete | Root/implementer/tester/reviewer | T-002..T-008 | Implementer boundary checks, then only missing tester checks and review verdicts are recorded. |
| T-010 | complete | Root | T-009B | Version, architecture and final sequential gates are recorded. |

## AC → task → test traceability

| AC | Tasks | Planned evidence |
|---|---|---|
| AC-001 | T-003,T-004,T-006 | GymRepositoryImplTest; GymEditorViewModelTest; GymEditorScreenTest |
| AC-002 | T-002,T-006 | EquipmentCatalogTest; GymEditorViewModelTest |
| AC-003 | T-006 | GymEditorViewModelTest; GymEditorScreenTest |
| AC-004 | T-006 | GymEditorViewModelTest; GymEditorScreenTest semantics |
| AC-005 | T-006 | GymEditorViewModelTest |
| AC-006 | T-006 | GymEditorViewModelTest; GymEditorScreenTest recreation/error |
| AC-007 | T-004,T-006 | GymRepositoryImplTest; GymEditorViewModelTest |
| AC-008 | T-002,T-004,T-007 | EquipmentCatalogTest; GymRepositoryImplTest; ExerciseEditorSheetTest |
| AC-009 | T-002,T-004 | EquipmentCatalogTest; GymRepositoryImplTest |
| AC-010 | T-004,T-008 | GymRepositoryImplTest; final unit suite |
| AC-011 | T-002,T-007 | ExerciseCatalogProjectionTest; ExerciseLibraryViewModelTest |
| AC-012 | T-003,T-004,T-006 | GymDaoTest; GymRepositoryImplTest; GymEditorViewModelTest |
| AC-013 | T-002,T-003 | EquipmentCatalogTest; Migration13To14Test |
| AC-014 | T-002,T-004,T-007,T-008 | ExerciseEditorSheetTest; GymRepositoryImplTest; final unit suite |
| AC-015 | T-004,T-006 | GymRepositoryImplTest; GymEditorViewModelTest |
| AC-016 | T-005,T-008 | GymSheetRowsTest; ExerciseSheetRowsTest; ConfigurationSheetsRepositoryTest; ConfigurationImportRepositoryTest |
| AC-017 | T-003,T-004,T-005,T-008 | Migration13To14Test; GymRepositoryImplTest; ConfigurationImportRepositoryTest |
| AC-018 | T-006,T-007 | GymEditorScreenTest; ExpandedGymEditorScreenTest; ExerciseEditorSheetComposeTest; ExpandedExerciseEditorSheetComposeTest; ExerciseLibraryScreenTest; ExerciseDetailScreenTest; AdaptiveNavigationTest |

## Commands and results

| Command | When | Result |
|---|---|---|
| Targeted `:app:testDebugUnitTest` с фильтрами 17 затронутых классов; повтор только исправленных | Root T-008 | PASS после исправлений; результаты отдельных запусков ниже |
| `./gradlew :app:testDebugUnitTest` | Root T-010, final after reported regression | PASS: 897 tests, 896 passed, 1 conditional skip, 0 failures/errors |
| `./gradlew :app:assembleDebug` | Root T-010, immediately after full tests | PASS; app/build/outputs/apk/debug/app-debug.apk |
| `./gradlew :app:assembleRelease` | Root T-010, persistence/schema release gate | BLOCKED at validateSigningRelease: all four signing inputs absent |

## Deviations

2026-09-07: владелец явно подтвердил все предложенные продуктовые решения и разрешил реализацию; первоначальный бриф сохранён как исходный документ.

## Findings

- **Resolved plan finding:** current code is Room v13 although `ARCHITECTURE.md` still says v12; root updates the
  document when it introduces v14.
- **Resolved plan finding:** existing `gym_exercises` represents legacy explicit availability; its meaning cannot safely be
  inferred as equipment inventory.
- **Resolved plan finding:** coverage metadata is unreliable for requirements; the canonical mapping must be a separate,
  exhaustive hand-maintained source.
- **Resolved plan finding:** custom requirement edits require the same atomic conflict protection as
  gym-inventory edits; Sheet parsers must distinguish unknown, explicit empty and specified IDs.

## Residual risks

- Release APK/R8 gate не завершён: отсутствуют RELEASE_KEYSTORE_FILE/storeFile, RELEASE_KEYSTORE_PASSWORD/storePassword, RELEASE_KEY_ALIAS/keyAlias, RELEASE_KEY_PASSWORD/keyPassword (keystore.properties/env). Подпись не обходилась.
- EmulatorV11CopyMigrationTest условно пропущен без внешней копии БД; автоматические v1→14, v13→14 и recovery v9/v10/v11 выполнены.
- Проверка на физическом устройстве и ручной TalkBack не выполнялись; Compose semantics compact/expanded/fontScale2 PASS.

- Q-001..Q-003 resolved by explicit owner confirmation on 2026-09-07.
- The proposed custom-unknown exclusion can reduce configured-gym catalog entries until the owner
  explicitly maps them; it is safer than silently making them equipment-free.
- No visual screenshot validation is planned; semantics/adaptive/font-scale tests provide the
  automated evidence permitted by repository instructions.

## Журнал выполнения

- 2026-09-07: строгий повторный review исправленных разделов: Gate P PASS, открытых P0/P1 замечаний плана нет.
- 2026-09-07: реализация поручена equipment_implementation; root владеет версией, архитектурой и трекером. Версия относительно main увеличена один раз: 21 / 1.3.13 → 22 / 1.3.14.

- 2026-09-07: root выделил явную разметку всех 263 ключей в CanonicalEquipmentRequirements.kt и создал gym-equipment-mapping.md. Автоматическая сверка ключей/уникальности/справочника пройдена; независимая содержательная проверка запланирована в финальном review. Ownership deviation: root owns only this mapping file/report; equipment_implementation сохраняет остальной код, Room целиком у одного владельца.

- 2026-09-07: API зафиксированы в коде; T-007 и catalog projection переданы equipment_library_implementation, остальной код/Room/Sheets/gym editor — equipment_implementation. Gradle запрещён до окончания обоих писателей; root выполнит общий targeted gate. Это допустимое ускорение skill после фиксации контрактов.

- 2026-09-07: интеграционная проверка выявила прямые вызовы старой availability-query в ActiveWorkoutRepositoryImpl (start/add). Файл и регрессионные тесты включены в T-004, владелец equipment_implementation; все пути запуска/import должны использовать тот же контракт покрываемых требований.

- 2026-09-07: независимое семантическое AC-013 ревью всех 263 строк: PASS (equipment_plan_review). Повторно сверены ключи/имена, отсутствие дублей и известность каждого ID; совместимость скамей и фиксации стоп подтверждена.

- 2026-09-07: ранний targeted `./gradlew :app:compileDebugKotlin` до параллельной записи завершился ошибкой suspend callable reference в GymRepositoryImpl; исправлено на suspend lambda. Повторная проверка ожидает оба среза. Первоначальная sandbox-попытка не получила доступ к Gradle lock; разрешённый escalated запуск дошёл до Kotlin compilation.

- 2026-09-07: migration fixture расширен реальными программами/связями/историей/подходами, добавлен путь v1→14 через ALL_MIGRATIONS. Проверки пока не запускались; результаты будут записаны после общего targeted gate.

- 2026-09-07: общий targeted test gate с фильтрами equipment/migration/repository/catalog/VM/sync/export не дошёл до тестов: compileDebugKotlin выявил два suspend-вызова (GymRepositoryImpl:292 и ExerciseDetailViewModel:131). Исправления распределены владельцам.
- 2026-09-07: после проверки полноты T-006 выявлены незавершённые copy/preview/exit guard и новые UI regression tests. Весь gyms UI/navigation и соответствующие тесты переданы equipment_library_implementation; equipment_implementation заканчивает data/sync/transaction tests. Это продолжение реализации; AC не отмечены выполненными без тестовых доказательств.

- 2026-09-07: общий targeted gate дошёл до исполнения: 141 tests / 6 failures / 0 errors. 2 legacy import behavior regressions, 3 outdated Sheets expected rows/header fixture, 1 font2 lazy-list test scroll. Миграции v13/v1→14, новая availability/rollback/idempotence/экспорт и выбранные VM/projection тесты прошли. Исправления распределены в одном батче data/UI.

- 2026-09-07: targeted correction gate: 58 тестов, 57 PASS; единственное падение — offscreen assertion GymEditorScreenTest при fontScale=2. Root добавил semantic scroll к строке и обратно к группе; отдельный `./gradlew :app:testDebugUnitTest --tests '*GymEditorScreenTest'` — PASS, 1 тест. ConfigurationImportRepositoryTest, ConfigurationSheetsRepositoryTest, ExerciseCatalogProjectionTest, ExerciseLibraryViewModelTest, ExerciseLibraryScreenTest прошли.
- 2026-09-07: добавлены явный фильтр «Без оборудования», его OR-семантика/сохранение/reset и сопоставление фильтра с capabilities (adjustable flat+incline, Nordic ankle anchor). UnknownLegacy не совпадает с empty. Группы редактора используют M3 TriStateCheckbox.
- 2026-09-07: Gate I PASS; equipment_test и equipment_plan_review выполняют независимые Gate T/V параллельно. Gradle у tester, root меняет только документацию до результатов.

- 2026-09-07: independent Gate T PASS. Tester добавил ExerciseEditorSheetComposeTest (compact/font2, UnknownLegacy → явное ExplicitNone → save), ExpandedExerciseEditorSheetComposeTest и ExpandedGymEditorScreenTest (w1200/font2). `./gradlew :app:testDebugUnitTest --tests '*ExerciseEditorSheetComposeTest' --tests '*ExpandedExerciseEditorSheetComposeTest' --tests '*ExpandedGymEditorScreenTest'` — BUILD SUCCESSFUL, 3 tests / 0 failures. Initial test compile typo QUADRICEPS исправлен на QUADS. Production не менялся.
- 2026-09-07: текущий strict review обнаружил потерю деталей конфликта при сохранении упражнения в library/detail UI; исправление и регрессионные тесты требуются до Gate V. База атомарно отклоняет конфликт; общий текст ошибки не выполняет AC-015.

- 2026-09-07: independent full production diff review Gate V FAIL: P0=0, P1=4, P2=1. Подтверждены: (1) потеря Conflict.details library/detail; (2) malformed v14 Sheets rows игнорируют несовместимые поля/invalid boolean и могут дать partial import; (3) group bulk при query+Selected охватывает скрытые невыбранные позиции; (4) disabled BackHandler пропускает navigation Back во время save/delete; (P2) conflict references включают незатронутые программы. Все findings распределены двум исходным owners в одном fix pass, без Gradle при параллельной записи.

- 2026-09-07: strict targeted rereview fix batch — Gate V PASS: P0=0/P1=0/P2=0. Все пять findings исправлены; reviewer подтвердил exhaustive conflict handling+draft, строгий malformed equipment import/preflight rollback, scoped group selection, consumed Back while busy и только затронутые routine references. Parser/import/VM/repository regressions добавлены; общий targeted run ожидает окончания записи tests.

- 2026-09-07: общий targeted gate после финального fix batch PASS: `./gradlew :app:testDebugUnitTest --tests '*GymSheetRowsTest' --tests '*ExerciseSheetRowsTest' --tests '*ConfigurationImportRepositoryTest' --tests '*GymRepositoryImplTest' --tests '*ExerciseLibraryViewModelTest' --tests '*ExerciseDetailViewModelTest' --tests '*GymEditorViewModelTest' --tests '*GymEditorScreenTest'` — 98 tests, 0 failures/errors/skips. Busy Back проверен при pending save и delete с завершением операции и единственным navigation event.

- 2026-09-07: первый финальный full `./gradlew :app:testDebugUnitTest` — 894 tests, 4 failures, 0 errors, 1 skipped. Все failures — устаревшие fixtures: MigrationRecoveryFixtures открывал текущую Room базу hardcoded v13 (3 класса); WorkoutImportRepositoryTest fake отдавал legacy rows лишь по старому range A:J вместо нового A:L. Исправления ограничены test fixtures, production не меняется. Единственный skip — условный EmulatorV11CopyMigrationTest, требующий внешнюю копию базы эмулятора. Failed full gate требует повторения после исправления fixtures; это документированное исключение к once-after-stable policy.

- 2026-09-07: второй full run — 894 tests, 1 failure, 1 skipped. Все первоначальные failures устранены, но изменение fixture v10 на variant surface было ошибочным: production v10→12 намеренно no-op для base-only схемы. Root восстановил исходную семантику v10, удаляя только v14 additions перед PRAGMA10. `./gradlew :app:testDebugUnitTest --tests '*Migration10To12Test'` — PASS. Production не менялся; повторный full gate запущен после подтверждённого targeted fix.

- 2026-09-07: полный unit gate PASS после fixture fixes: 894 tests / 0 failures / 0 errors / 1 conditional skip (893 passed). `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL. Release tool call прерван пользователем; активного wrapper/build process после прерывания нет.
- 2026-09-07: пользователь обнаружил AC-010/015 integration regression в routine editor: выбор configured-зала помечает все упражнения unavailable, хотя picker корректен. Root подтвердил RoutineEditorViewModel.recalculateConflicts обращается к legacy gym.exercises. UI owner исправляет VM/test через общий reactive observeAvailableExercises; strict reviewer проверяет оставшиеся bypasses. Предыдущие Gate T/V не обнаружили этот путь; после исправления нужны targeted и повторные финальные gates. Версия остаётся единственным инкрементом текущей незавершённой фичи 22/1.3.14.

- 2026-09-07: пользовательский routine regression исправлен через flatMapLatest observeAvailableExercises с input guard; без залов conflicts пусты, metadata-only updates не сбрасывают pending/conflicts, isCheckingAvailability блокирует преждевременное сохранение. RoutineEditorViewModelTest targeted PASS; первоначальный compile type inference emptySet исправлен явным Long. Bounded strict rereview PASS, дополнительных active legacy availability bypasses не найдено. Root добавил явный ExperimentalCoroutinesApi opt-in.
- 2026-09-07: app/build.gradle.kts внешне изменён на 23/1.3.15 во время пользовательской проверки; ни root, ни implementer этот второй инкремент не делали. Значение сохранено как пользовательское/внешнее изменение; повторно не повышалось. Финальные artifacts собираются с 23/1.3.15.

- Финальная верификация после пользовательского исправления: `./gradlew :app:testDebugUnitTest` — BUILD SUCCESSFUL, 897 tests / 0 failures / 0 errors / 1 skip; `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL; `./gradlew :app:assembleRelease` — validateSigningRelease BLOCKED (не заданы все четыре signing inputs). Bounded review PASS, P0/P1/P2=0. AC-001..018 delivered; встроенный каталог без custom equipment CRUD по согласованному scope.
