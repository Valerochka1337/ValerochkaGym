# ValerochkaGym — план финальной полировки (final-polish)

**Задача**: довести ValerochkaGym до уровня «AAA polish»: сначала закрыть пробелы в тестах
(зафиксировать поведение), затем исправить узкие места производительности и выпилить мёртвый код,
унифицировать все анимации на пружинах Material 3 Expressive motionScheme через общий файл
моушн-токенов, добавить семантическую хаптику по всему приложению, расширить настройки
(автостарт таймера отдыха, общий виброотклик UI, секция «Данные»: экспорт БД, очистка,
«О приложении»), включить R8 для release-сборки и обновить/создать документацию
(README, design-system, ARCHITECTURE, CLAUDE.md, CHANGELOG).

**Зафиксированные решения** (не пересматривать):

*   Никаких сторонних анимационных библиотек (запрещено design-system.md).
*   Никаких kg/lbs-настроек.
*   Строки UI остаются захардкоженными в Kotlin — только дедупликация повторов.
*   Данные пользователя неприкосновенны: только Room-миграции, никакого destructive fallback.
*   Логирование: осознанно остаётся нулевым (ошибки синка видны в UI через
    UploadStatusBadge/ImportResult) — фиксируем в ARCHITECTURE.md.
*   Predictive back: остаёмся на системном уровне; seekable-переходы не тащим — фиксируем
    как известное ограничение в ARCHITECTURE.md.
*   Backup: `allowBackup=true` сохраняем — в `gym.db` и DataStore нет токенов (авторизация через
    CredentialManager), а бэкап переживает переустановку; шаблонные комментарии в
    `backup_rules.xml`/`data_extraction_rules.xml` заменяем на явные правила с пояснением.
*   `vibrationEnabled` остаётся настройкой вибрации уведомления таймера отдыха; UI-хаптику
    гейтит новый `haptics_enabled`.

**Структура плана**: git-подготовка (1) → тесты на существующее поведение (2–6) →
производительность и рефакторинг (7–8) → моушн-токены и анимации (9–11) → хаптика (12) →
настройки (13–14) → release-конфигурация (15) → документация и финальная проверка (16).

Каждая стадия — отдельный коммит на ветке `feature/final-polish` (сообщения на русском,
в конце `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`).

## План выполнения

### Стадия 1: Git-подготовка

**Что сделать:**

*   Влить `feature/body-heatmap-highlighter` в `main` (fast-forward).
*   Создать ветку `feature/final-polish` от `main`.
*   Сохранить этот план в `vibe/final-polish-plan.md`, создать `vibe/final-polish-plan-track.md`.
*   Устаревшие локальные ветки (feat/analysis-tab, feat/calendar, feat/calendar-tab,
    feat/redesign-material3-single-accent, feat/ui-analysis-tab-settings-circle,
    feature/body-heatmap-highlighter) и worktree feat-calendar — удаление отложено:
    требуется явное подтверждение пользователя (запрещено политикой auto mode).

**Команды проверки:**

*   `git log --oneline -3 main`, `git branch --show-current`
*   `./gradlew :app:testDebugUnitTest` (базовая линия зелёная)

### Стадия 2: Тесты чистой доменной логики (MuscleLandmarks, CardioMet, MuscleDefaults)

**Что добавить/реализовать:**

*   `MuscleLandmarksTest`: тесты `zoneFor`, `landmarks`, `setWeightFor` — граничные значения зон.
*   `CardioMetTest`: формулы MET для всех кардио-типов, граничные скорости/уклоны.
*   `MuscleDefaultsTest`: `defaultMuscleLoads` — полнота покрытия групп, разумность долей.
*   Стиль: JUnit4, имена в backtick-предложениях, без тестов приватных методов.

**Файлы:**

*   `app/src/test/java/com/valerochka1337/valerochkagym/domain/analysis/MuscleLandmarksTest.kt` — создать
*   `app/src/test/java/com/valerochka1337/valerochkagym/domain/analysis/CardioMetTest.kt` — создать
*   `app/src/test/java/com/valerochka1337/valerochkagym/data/db/MuscleDefaultsTest.kt` — создать

**Примеры в существующем коде:**

*   `app/src/test/java/com/valerochka1337/valerochkagym/domain/analysis/OneRepMaxTest.kt`

**Команды проверки:**

*   `./gradlew :app:testDebugUnitTest --tests "*MuscleLandmarksTest" --tests "*CardioMetTest" --tests "*MuscleDefaultsTest"`

### Стадия 3: Тесты форматтеров

**Что добавить/реализовать:**

*   `AnalysisFormatTest`: форматтеры из `ui/analysis/AnalysisFormat.kt`.
*   `WorkoutFormatTest`: форматтеры из `ui/common/WorkoutFormat.kt`.
*   `SetFormattingTest`: `formatSet` из `domain/SetFormatting.kt` — все типы подходов.
*   `ChartCommonTest`: `formatAxisValue` из `ui/analysis/charts/ChartCommon.kt`.

**Файлы:**

*   `app/src/test/java/com/valerochka1337/valerochkagym/ui/AnalysisFormatTest.kt` — создать
*   `app/src/test/java/com/valerochka1337/valerochkagym/ui/WorkoutFormatTest.kt` — создать
*   `app/src/test/java/com/valerochka1337/valerochkagym/domain/SetFormattingTest.kt` — создать
*   `app/src/test/java/com/valerochka1337/valerochkagym/ui/ChartCommonTest.kt` — создать

**Примеры в существующем коде:**

*   `app/src/test/java/com/valerochka1337/valerochkagym/ui/CalendarFormatTest.kt`,
    `.../domain/EnumDisplayTest.kt`

**Команды проверки:**

*   `./gradlew :app:testDebugUnitTest --tests "*AnalysisFormatTest" --tests "*WorkoutFormatTest" --tests "*SetFormattingTest" --tests "*ChartCommonTest"`

### Стадия 4: Тесты ActiveWorkoutViewModel

**Что добавить/реализовать:**

*   `ActiveWorkoutViewModelTest`: загрузка активной тренировки, завершение/отмена подхода,
    ± степперы, рест-действия, finish/discard, добавление упражнения. Тикер — по паттерну
    `CalendarViewModelTest`, `MainDispatcherRule`, приватные фейки.

**Файлы:**

*   `app/src/test/java/com/valerochka1337/valerochkagym/ui/ActiveWorkoutViewModelTest.kt` — создать

**Примеры в существующем коде:**

*   `app/src/test/java/com/valerochka1337/valerochkagym/ui/WorkoutsViewModelTest.kt`
*   `app/src/test/java/com/valerochka1337/valerochkagym/ui/CalendarViewModelTest.kt`

**Команды проверки:**

*   `./gradlew :app:testDebugUnitTest --tests "*ActiveWorkoutViewModelTest"`

### Стадия 5: Тесты остальных ViewModel

**Что добавить/реализовать:**

*   `WorkoutDetailViewModelTest`, `WorkoutSummaryViewModelTest` (включая сортировку дерева
    тренировки — понадобится зелёной для дедупликации в Стадии 8), `MainScaffoldViewModelTest`.

**Файлы:**

*   `app/src/test/java/com/valerochka1337/valerochkagym/ui/WorkoutDetailViewModelTest.kt` — создать
*   `app/src/test/java/com/valerochka1337/valerochkagym/ui/WorkoutSummaryViewModelTest.kt` — создать
*   `app/src/test/java/com/valerochka1337/valerochkagym/ui/MainScaffoldViewModelTest.kt` — создать

**Примеры в существующем коде:**

*   `app/src/test/java/com/valerochka1337/valerochkagym/ui/WorkoutsViewModelTest.kt`

**Команды проверки:**

*   `./gradlew :app:testDebugUnitTest --tests "*WorkoutDetailViewModelTest" --tests "*WorkoutSummaryViewModelTest" --tests "*MainScaffoldViewModelTest"`

### Стадия 6: Тесты воркера, планировщика и AppIconManager

**Что добавить/реализовать:**

*   Добавить `androidx.work:work-testing` (testImplementation, линейка 2.11.2).
*   `UploadWorkoutWorkerTest` (Robolectric + `TestListenableWorkerBuilder`): success/retry/failure,
    граница `runAttemptCount < MAX_ATTEMPTS`.
*   `UploadSchedulerTest`: `scheduleAllPending` — сброс статусов и количество.
*   `AppIconManagerTest` (Robolectric): гейтинг переключения alias по foreground-состоянию,
    насколько эмулируется; иначе — вычисление целевого alias.

**Файлы:**

*   `gradle/libs.versions.toml`, `app/build.gradle.kts` — work-testing
*   `app/src/test/java/com/valerochka1337/valerochkagym/worker/UploadWorkoutWorkerTest.kt` — создать
*   `app/src/test/java/com/valerochka1337/valerochkagym/worker/UploadSchedulerTest.kt` — создать
*   `app/src/test/java/com/valerochka1337/valerochkagym/data/appicon/AppIconManagerTest.kt` — создать

**Примеры в существующем коде:**

*   `app/src/test/java/com/valerochka1337/valerochkagym/data/RoomDaoTest.kt` — Robolectric-паттерн

**Команды проверки:**

*   `./gradlew :app:testDebugUnitTest` (полный прогон — покрытие зафиксировано)

### Стадия 7: Производительность

**Что добавить/реализовать:**

*   `AnalysisViewModel`: `flowOn(Dispatchers.Default)` перед `stateIn` — анализ уходит с Main.
*   `BodyMap.kt`: ленивый кэш `ParsedBody` в companion (не парсить SVG в композиции повторно).
*   Ключи LazyColumn: `WorkoutDetailScreen`, `WorkoutSummaryScreen` (два списка).
*   `MuscleLoadCards.kt`: `remember` для производных вычислений.
*   `WorkoutSessionService`: слить коллекторы нотификации в один `combine`; рингтон с Main-потока.

**Файлы:**

*   `ui/analysis/AnalysisViewModel.kt`, `ui/analysis/body/BodyMap.kt`,
    `ui/history/WorkoutDetailScreen.kt`, `ui/summary/WorkoutSummaryScreen.kt`,
    `ui/analysis/MuscleLoadCards.kt`, `service/WorkoutSessionService.kt`

**Команды проверки:**

*   `./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug`

### Стадия 8: Рефакторинг — мёртвый код, дубли, устаревшие комментарии

**Что добавить/реализовать:**

*   Удалить мёртвые DAO-методы и их тесты (перепроверить grep-ом): `ScheduledWorkoutDao.observeUpcoming`
    + `SCHEDULED_GRACE_MILLIS`, `WorkoutDao.observeWorkoutVolumes` + `WorkoutVolume`,
    `ExerciseDao.getByIds`, `WorkoutDao.updateWorkout`/`updateWorkoutExercise`,
    `RoutineDao.insertRoutineExercise`/`updateRoutineExercise`/`deleteRoutineExercise`.
*   `classifyHttp` → общий `data/google/HttpErrorClassifier.kt`.
*   Общая сортировка дерева тренировки (`sortedWorkoutFull`) вместо дубля в `WorkoutSummaryViewModel`.
*   `WorkoutImportRepository`: rethrow `CancellationException`; счётчик пропущенных строк в результате.
*   `themes.xml`: `windowBackground` = актуальный `GymBlack`.
*   `backup_rules.xml`/`data_extraction_rules.xml`: явные правила вместо шаблонов.
*   Стейл-комментарии: `strings.xml` («плейсхолдер» при реальном ID), KDoc `ExerciseMuscleEntity`.

**Команды проверки:**

*   `./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug`

### Стадия 9: Моушн-токены и миграция существующих анимаций

**Что добавить/реализовать:**

*   `ui/theme/Motion.kt` (`GymMotion`): composable-аксессоры к `MaterialTheme.motionScheme`
    (spatial/effects × fast/default/slow) + статические `NavSlideSpec`/`NavFadeSpec`/`TabFadeSpec`
    (перенос из `GymNavGraph`; KDoc — почему статические).
*   Миграция: `GymNavGraph` (импорт из Motion.kt), `MainScaffold` (баннер+навбар),
    рест-пилюля `ActiveWorkoutScreen`, `MuscleLoadCards.animateContentSize`, `BodyMap.Crossfade`.

**Команды проверки:**

*   `./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug`

### Стадия 10: Анимация графиков и календаря

**Что добавить/реализовать:**

*   Графики (`ColumnChart`, `TrendLineChart`, `ZoneMeter`): reveal-фракция `Animatable(0f)` с
    перезапуском по ключу данных → `animateTo(1f, spatial-спек)`; высоты/путь/заполнение × фракция.
*   Календарь (`MonthGrid`): направленный `AnimatedContent` по `YearMonth` (вперёд — влево,
    назад — вправо, спринг из `GymMotion`, `SizeTransform`); свайп сохраняется.

**Команды проверки:**

*   `./gradlew :app:testDebugUnitTest --tests "*AnalysisRenderTest"` (+ полный прогон)

### Стадия 11: Микропереходы

**Что добавить/реализовать:**

*   Рамка выбранной карточки рутины и чипы — `animateColorAsState`.
*   `animateItem()` для списков: Workouts, WorkoutDetail, WorkoutSummary, ExerciseLibrary.
*   `UploadStatusBadge`: смена статуса через `AnimatedContent` (fade+scale).
*   Fade-in контента после загрузки вместо «выскакивания» на главных экранах.

**Команды проверки:**

*   `./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug`

### Стадия 12: Хаптика — GymHaptics + настройка haptics_enabled

**Что добавить/реализовать:**

*   `SettingsRepository`: ключ `haptics_enabled` (default true), поле в `GymSettings`, сеттер.
*   `ui/haptics/GymHaptics.kt`: семантическая обёртка `LocalHapticFeedback`
    (tap/confirm/toggle/step/stepFrequent/success/reject/longPress → `HapticFeedbackType`),
    no-op при выключенной настройке; `LocalGymHaptics` + прокидывание из корня.
*   Сайты: завершение подхода confirm, отмена toggle(false), степперы step/stepFrequent
    (включая long-press), рест-пилюля, finish success / discard reject, табы tap, выбор рутины,
    тап по мышце BodyMap, PR success, степпер отдыха в настройках.
*   Тесты: `GymHapticsTest`, дополнить `SettingsViewModelTest`.

**Команды проверки:**

*   `./gradlew :app:testDebugUnitTest --tests "*GymHapticsTest" --tests "*SettingsViewModelTest"` (+ полный)

### Стадия 13: Автостарт таймера отдыха

**Что добавить/реализовать:**

*   `SettingsRepository`: ключ `rest_autostart` (default true), поле, сеттер.
*   Гейт старта отдыха при завершении подхода (точку сверить по коду: `CompleteSetUseCase` /
    `ActiveWorkoutViewModel`); ручной старт остаётся.
*   Тесты: `CompleteSetUseCaseTest`, `ActiveWorkoutViewModelTest`, `SettingsViewModelTest`.

**Команды проверки:**

*   `./gradlew :app:testDebugUnitTest --tests "*CompleteSetUseCaseTest" --tests "*ActiveWorkoutViewModelTest" --tests "*SettingsViewModelTest"`

### Стадия 14: Экран настроек — группа отдыха, виброотклик, секция «Данные»

**Что добавить/реализовать:**

*   Карточка «Таймер отдыха»: тумблер «Автостарт после подхода»; уточнить подписи
    звука/вибрации («…по окончании», «Вибрация уведомления»).
*   Тумблер «Виброотклик» (`hapticsEnabled`).
*   Карточка «Данные»: экспорт БД через SAF `ACTION_CREATE_DOCUMENT`
    (`data/backup/DatabaseExporter.kt`: `PRAGMA wal_checkpoint(TRUNCATE)` + копия `gym.db` на IO);
    «Очистить данные» с confirm-диалогом (`data/backup/ClearDataUseCase.kt`: `clearAllTables` +
    повторный посев + отмена очереди WorkManager; настройки не трогаем);
    «О приложении» (versionName через PackageManager).
*   `SettingsViewModel`: `exportDatabase(uri)`, `clearAllData()`, снэкбар-сообщения.
*   Тесты: `SettingsViewModelTest` (дополнить), `DatabaseExporterTest` (Robolectric).

**Команды проверки:**

*   `./gradlew :app:testDebugUnitTest --tests "*SettingsViewModelTest" --tests "*DatabaseExporterTest"` (+ полный)

### Стадия 15: Release-конфигурация

**Что добавить/реализовать:**

*   `app/build.gradle.kts`: release — `optimization { enable = true }`, `versionCode = 2`,
    `versionName = "1.1.0"`.
*   Keep-правила для kotlinx-serialization DTO (`SheetsApi`/`CalendarApi`); Hilt/Room/WorkManager —
    ожидаются consumer-rules, проверить сборкой. При падении R8 — дополнять правила.

**Команды проверки:**

*   `./gradlew :app:assembleRelease && ./gradlew :app:testDebugUnitTest`

### Стадия 16: Документация, CHANGELOG и финальная проверка

**Что добавить/реализовать:**

*   `README.md`: убрать «плейсхолдер»-формулировки про client ID, новые настройки, release-сборка.
*   `docs/design-system.md`: секции Motion (`GymMotion`) и Haptics (`GymHaptics`);
    фикс ссылки `ChartSpec.kt` → `ChartCommon.kt`.
*   `ARCHITECTURE.md` — создать: слои, потоки данных, фоновые механизмы, осознанные решения
    (zero-logging, системный predictive back, включённый backup, dark-only + activity-alias).
*   `CLAUDE.md` — создать: стек, команды, конвенции тестов и кода.
*   `CHANGELOG.md` — создать (Keep a Changelog): 1.1.0 и 1.0.
*   Трекер — все стадии `[X]`. Полный прогон тестов + debug + release. EOL/trailing spaces.
*   Итог остаётся на `feature/final-polish`; merge в `main` — по команде пользователя.

**Команды проверки:**

*   `./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug && ./gradlew :app:assembleRelease`
*   `git status`, `git log --oneline`
