# ValerochkaGym — план реализации v1

> **Для агентов-исполнителей:** выполнять по стадиям строго последовательно, трекинг — `vibe/valerochka-gym-plan-track.md` (создать при старте, формат `[ ]`/`[X]`). Спека: `docs/superpowers/specs/2026-07-31-valerochka-gym-design.md`.

**Задача**: Android-приложение учёта тренировок (Kotlin, Jetpack Compose, Material 3 Expressive, тёмная тема «C»): программы-шаблоны, активная тренировка с таймером отдыха (foreground service), история, выгрузка в Google Sheets (строка = подход, WorkManager) и планирование в Google Calendar. Один пользователь, один Gradle-модуль `app`, пакет `com.valerochka1337.valerochkagym`.

**Структура плана**: каркас и дизайн-система (1–3) → данные (4–5) → библиотека упражнений (6–7) → программы (8–9) → активная тренировка (10–14) → история (15) → Google: вход, Sheets, Calendar (16–21) → полировка и документация (22–23).

**Ключевые версии** (проверены на совместимость с AGP 9.3.1, июль 2026): Kotlin — встроенный в AGP (НЕ применять `org.jetbrains.kotlin.android`!), `org.jetbrains.kotlin.plugin.compose 2.3.20`, KSP `2.3.10`, Compose BOM `2026.06.00` + пин `material3:1.5.0-alpha24` (Expressive, `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`), Hilt `2.59.2` (ровно, 2.59 сломан), androidx.hilt `1.4.0`, Room `2.8.4` (+ gradle-плагин `androidx.room`), Navigation Compose `2.9.8`, WorkManager `2.11.2` (артефакт `work-runtime`), DataStore `1.2.1`, credentials `1.6.0` + `googleid 1.2.0` (свежие обязательны — старые падают с SecurityException на API 36), play-services-auth `21.4.0` (AuthorizationClient), Retrofit `3.x` + OkHttp + kotlinx-serialization `1.11.0`, coroutines `1.11.0`, Robolectric (тесты Room на JVM).

## План выполнения

### Стадия 1: Перевод проекта на Kotlin/Compose (Gradle-каркас)

**Что добавить/реализовать:**

*   Переписать `gradle/libs.versions.toml`: все версии и библиотеки из блока «Ключевые версии», плагины `android-application` (AGP 9.3.1), `kotlin-compose`, `ksp`, `hilt`, `room`, `kotlin-serialization`.
*   `app/build.gradle.kts`: применить плагины (без `kotlin.android` — встроенный Kotlin AGP 9), `buildFeatures { compose = true }`, deps: compose-bom + ui/material3 (пин alpha)/tooling, activity-compose, navigation-compose, lifecycle-viewmodel-compose, hilt + hilt-navigation-compose + hilt-work + androidx-hilt-compiler (ksp), room-runtime/ktx + room-compiler (ksp), work-runtime, datastore-preferences, credentials + credentials-play-services-auth + googleid, play-services-auth, retrofit + converter-kotlinx-serialization + okhttp, kotlinx-serialization-json, coroutines; testImplementation: junit, robolectric, room-testing, coroutines-test, androidx-test-core.
*   Удалить java-шаблон (`MainActivity.java`, если есть), создать `MainActivity.kt` (ComponentActivity + `setContent { Text("ValerochkaGym") }`), `GymApplication.kt` с `@HiltAndroidApp`, зарегистрировать в манифесте.
*   `kotlin { jvmToolchain(17) }` вместо compileOptions Java 11.

**Файлы:**

*   `gradle/libs.versions.toml` — полная замена
*   `app/build.gradle.kts` — полная замена
*   `app/src/main/java/com/valerochka1337/valerochkagym/MainActivity.kt`, `GymApplication.kt` — создать
*   `app/src/main/AndroidManifest.xml` — application name, activity

**Команды проверки:**

*   `./gradlew :app:assembleDebug`

### Стадия 2: Дизайн-система (тема «C»)

**Что добавить/реализовать:**

*   `ui/theme/Color.kt`: `GymBlack=0xFF101413`, `GymSurface=0xFF12211E`, `GymSurfaceTop=0xFF173029`, `Teal=0xFF2DD4BF`, `TealLight=0xFF5EEAD4`, `Peach=0xFFFFC4AA`, `TextPrimary=0xFFEEF7F4`, `TextSecondary=0xFF5F7370`, `TextTertiary=0xFF6B807C`.
*   `ui/theme/Theme.kt`: `GymTheme` на `MaterialExpressiveTheme` (`@OptIn(ExperimentalMaterial3ExpressiveApi::class)`) с тёмной `darkColorScheme(primary=Teal, secondary=Peach, background=GymBlack, surface=GymSurface, onPrimary=0xFF04241F, ...)`; только тёмная тема.
*   `ui/theme/Type.kt`: крупные жирные заголовки (headlineLarge 28sp/ExtraBold, letterSpacing −0.5sp).
*   `ui/components/GlowBackground.kt`: composable-обёртка, рисующая 2 радиальных пятна (`Brush.radialGradient`, бирюза 0.30f alpha справа-сверху, персик 0.18f слева-снизу) под контентом.
*   `ui/components/GymCard.kt`: карточка с градиентом `GymSurfaceTop→GymSurface` и асимметричным скруглением `RoundedCornerShape(24.dp, 24.dp, 24.dp, 8.dp)`.
*   `ui/components/PillButton.kt`: кнопка-пилюля с горизонтальным градиентом `Teal→TealLight`, тёмный текст.
*   MainActivity: обернуть в `GymTheme` + `GlowBackground`, превью-контент с карточкой и кнопкой.

**Документация:**

*   https://developer.android.com/develop/ui/compose/designsystems/material3 (M3 Expressive в Compose)

**Команды проверки:**

*   `./gradlew :app:assembleDebug`

### Стадия 3: Навигация и каркас экранов

**Что добавить/реализовать:**

*   `ui/navigation/GymNavGraph.kt`: Navigation Compose; routes: `workouts`, `history`, `settings`, `library`, `routine_editor/{routineId}`, `active_workout`, `workout_summary/{workoutId}`, `workout_detail/{workoutId}`.
*   `ui/navigation/MainScaffold.kt`: `Scaffold` с `NavigationBar` (3 вкладки: Тренировки / История / Настройки, иконки Material), NavHost внутри.
*   Экраны-заглушки: `ui/workouts/WorkoutsScreen.kt`, `ui/history/HistoryScreen.kt`, `ui/settings/SettingsScreen.kt` (заголовок в стиле темы).

**Команды проверки:**

*   `./gradlew :app:assembleDebug`

### Стадия 4: Room, DataStore, сидирование упражнений

**Что добавить/реализовать:**

*   `data/db/entity/`: `ExerciseEntity` (id, name, muscleGroup: enum `MuscleGroup` {CHEST, BACK, LEGS, SHOULDERS, ARMS, CORE, CARDIO, FULL_BODY}, type: enum `ExerciseType` {STRENGTH, TIMED, CARDIO}, isCustom), `RoutineEntity` (id, name, note), `RoutineExerciseEntity` (id, routineId FK cascade, exerciseId FK, position, restSeconds: Int?, plannedSetsJson: String), `ScheduledWorkoutEntity` (id, routineId FK cascade, dateTimeMillis, calendarEventId), `WorkoutEntity` (id: String UUID, routineId FK set-null nullable, name, startedAt, finishedAt nullable, note, uploadStatus: enum {PENDING, UPLOADED, FAILED}, uploadError nullable), `WorkoutExerciseEntity` (id, workoutId FK cascade, exerciseId FK, position), `WorkoutSetEntity` (id, workoutExerciseId FK cascade, setIndex, weightKg: Double?, reps: Int?, durationSec: Int?, speedKmh: Double?, inclinePct: Double?, isCompleted).
*   `data/db/PlannedSet.kt`: `@Serializable data class PlannedSet(weightKg, reps, durationSec, speedKmh, inclinePct — все nullable)`; конвертация list↔JSON через kotlinx-serialization в `Converters.kt` (+ enum-конвертеры).
*   DAO: `ExerciseDao` (getAll: Flow, search по имени+фильтр по группе, insert, getById), `RoutineDao` (routines с count упражнений: Flow, getRoutineWithExercises, upsert/delete, insert/delete/update RoutineExercise), `WorkoutDao` (insert/update workout+exercises+sets, getActiveWorkout (`finishedAt IS NULL`), getFinishedWorkouts: Flow, getWorkoutFull(id) c `@Relation`-деревом `WorkoutFull`, lastCompletedSetsForExercise(exerciseId): подходы этого упражнения из последней завершённой тренировки, maxCompletedWeight(exerciseId), setUploadStatus), `ScheduledWorkoutDao` (upcoming: Flow, insert, delete).
*   `data/db/GymDatabase.kt`: `@Database(entities=[...], version=1)`; `RoomDatabase.Callback.onCreate` → вставка сида.
*   `data/db/SeedExercises.kt`: `val seedExercises: List<ExerciseEntity>` — ~70 упражнений на русском по всем группам (жимы, тяги, приседания, выпады, подтягивания, планка (TIMED), беговая дорожка (CARDIO) и т.д.).
*   `data/settings/SettingsRepository.kt`: DataStore-препараты: `googleEmail: String?`, `spreadsheetId: String?`, `defaultRestSeconds=120`, `soundEnabled=true`, `vibrationEnabled=true` — Flow + suspend-сеттеры.
*   `di/DataModule.kt`: Hilt-провайдеры БД, DAO, DataStore.

**Команды проверки:**

*   `./gradlew :app:assembleDebug`

### Стадия 5: Тесты слоя данных

**Что добавить/реализовать:**

*   Robolectric-тесты DAO на in-memory БД (`Room.inMemoryDatabaseBuilder`): сид загружается (70+ упражнений); `lastCompletedSetsForExercise` возвращает подходы именно последней завершённой тренировки и игнорирует незавершённые; `getActiveWorkout` — одна активная; каскадное удаление Routine→RoutineExercise и Workout→WorkoutExercise→WorkoutSet; конвертация plannedSetsJson туда-обратно.

**Файлы:**

*   `app/src/test/java/com/valerochka1337/valerochkagym/data/WorkoutDaoTest.kt`, `RoutineDaoTest.kt`, `ExerciseDaoTest.kt`, `ConvertersTest.kt`

**Команды проверки:**

*   `./gradlew :app:testDebugUnitTest`

### Стадия 6: Библиотека упражнений (UI)

**Что добавить/реализовать:**

*   `ui/library/ExerciseLibraryViewModel.kt`: StateFlow списка с поиском (query) и фильтром по `MuscleGroup` (чипы), `createCustomExercise(name, group, type)`.
*   `ui/library/ExerciseLibraryScreen.kt`: поле поиска, ряд фильтр-чипов групп, LazyColumn упражнений (имя, группа, тип), FAB «+» → диалог создания своего (имя, выбор группы, выбор типа). Режим выбора: экран открывается с колбэком `onExerciseSelected` для использования из редактора программы; standalone-режим из настроек не нужен.

**Команды проверки:**

*   `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`

### Стадия 7: Тесты библиотеки

**Что добавить/реализовать:**

*   `ExerciseLibraryViewModelTest`: фильтрация по query и группе (fake DAO/репозиторий), создание своего упражнения ставит `isCustom=true`.

**Файлы:**

*   `app/src/test/java/com/valerochka1337/valerochkagym/ui/ExerciseLibraryViewModelTest.kt`

**Команды проверки:**

*   `./gradlew :app:testDebugUnitTest`

### Стадия 8: Программы (список + редактор)

**Что добавить/реализовать:**

*   `ui/workouts/WorkoutsViewModel.kt`: Flow программ (название, кол-во упражнений, ~длительность = Σ подходов × (45 сек + отдых)), действия: запустить, дублировать, удалить.
*   `WorkoutsScreen.kt` (боевой): список `GymCard`-карточек программ (меню: редактировать/дублировать/запланировать(заглушка до Стадии 20)/удалить с подтверждением), `PillButton` «▶ Начать тренировку» (по выбранной программе), текстовая кнопка «Пустая тренировка», пустое состояние.
*   `ui/routine/RoutineEditorViewModel.kt` + `RoutineEditorScreen.kt`: имя программы, список упражнений (карточка: имя, plannedSets-редактор — добавить/удалить подход, целевые значения по типу упражнения, поле отдыха сек), добавление упражнения → навигация в библиотеку с выбором, перестановка вверх/вниз кнопками, удаление, сохранение (upsert всего дерева).

**Команды проверки:**

*   `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`

### Стадия 9: Тесты программ

**Что добавить/реализовать:**

*   `RoutineEditorViewModelTest` (fake-репозиторий): добавление/удаление/перестановка упражнений, сохранение дерева, оценка длительности; `WorkoutsViewModelTest`: дублирование создаёт копию с суффиксом «(копия)».

**Файлы:**

*   `app/src/test/java/com/valerochka1337/valerochkagym/ui/RoutineEditorViewModelTest.kt`, `WorkoutsViewModelTest.kt`

**Команды проверки:**

*   `./gradlew :app:testDebugUnitTest`

### Стадия 10: Домен активной тренировки

**Что добавить/реализовать:**

*   `domain/ActiveWorkoutRepository.kt` (интерфейс + Room-реализация `data/ActiveWorkoutRepositoryImpl`): `startFromRoutine(routineId)` — создаёт Workout+WorkoutExercise+WorkoutSet из plannedSets (isCompleted=false), значения предзаполняются «прошлым разом» (fallback — plannedSet); `startEmpty()`; `getActive(): Flow<WorkoutFull?>`; `updateSet`, `toggleSetCompleted`, `addSet` (копия значений предыдущего подхода), `deleteSet`, `addExercise`, `deleteExercise`, `finish(workoutId)` — удаляет пустые неотмеченные подходы, ставит finishedAt; `discard(workoutId)`.
*   `domain/PreviousSetsUseCase.kt`: по exerciseId → список подходов последней завершённой тренировки (для строки «прошлый: 30×10, 30×9»).
*   `domain/WorkoutStatsUseCase.kt`: `volume(workout)` = Σ(weightKg×reps) по выполненным STRENGTH-подходам; `newPrs(workout)`: упражнения, где max выполненный вес тренировки > исторического max до неё.
*   `domain/RoutineUpdateUseCase.kt`: `hasDiverged(workout, routine)` (сравнение состава/числа подходов/значений с plannedSets) и `applyToRoutine(workout, routineId)` — перезапись plannedSets фактическими выполненными значениями.

**Команды проверки:**

*   `./gradlew :app:assembleDebug`

### Стадия 11: Тесты домена

**Что добавить/реализовать:**

*   Robolectric + in-memory Room: `startFromRoutine` предзаполняет из «прошлого раза» при его наличии и из plannedSets иначе; `finish` отбрасывает пустые; volume считает только выполненные силовые; `newPrs` фиксирует новый максимум и молчит при равенстве; `hasDiverged`/`applyToRoutine`.

**Файлы:**

*   `app/src/test/java/com/valerochka1337/valerochkagym/domain/ActiveWorkoutRepositoryTest.kt`, `WorkoutStatsUseCaseTest.kt`, `RoutineUpdateUseCaseTest.kt`

**Команды проверки:**

*   `./gradlew :app:testDebugUnitTest`

### Стадия 12: UI активной тренировки (вариант B)

**Что добавить/реализовать:**

*   `ui/active/ActiveWorkoutViewModel.kt`: state = WorkoutFull + индекс текущего упражнения/подхода + строка «прошлый раз»; методы-делегаты в репозиторий; степперы: вес ±2.5 (долгое нажатие ±0.5), повторы ±1, длительность ±15 сек, скорость ±0.5, наклон ±0.5.
*   `ui/active/ActiveWorkoutScreen.kt`: шапка (название, таймер тренировки, «упражнение N из M»), пейджер/список упражнений; в упражнении: выполненные подходы — свёрнутые пилюли, текущий — крупная карточка со степперами (поля по типу упражнения: STRENGTH — вес/повторы; TIMED — длительность; CARDIO — скорость/наклон/длительность; тап по числу — клавиатурный ввод) и `PillButton` «Подход выполнен ✓», будущие — неактивные пилюли; «+ Добавить подход», добавление упражнения (в библиотеку), удаление свайпом/меню; кнопки «Завершить» (диалог) и «Отменить тренировку» (диалог).
*   `FLAG_KEEP_SCREEN_ON` на время экрана (`DisposableEffect`).
*   Восстановление: `MainScaffold` при `getActive() != null` показывает закреплённый баннер «Тренировка идёт — вернуться»; запуск новой при активной невозможен.
*   `ui/summary/WorkoutSummaryScreen.kt` + ViewModel: длительность, объём, список PR; если `hasDiverged` — диалог «Обновить программу?» (да → applyToRoutine); кнопка «Готово» → главная. (Запуск выгрузки добавится в Стадии 19.)

**Команды проверки:**

*   `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`

### Стадия 13: Foreground service и таймер отдыха

**Что добавить/реализовать:**

*   `service/RestTimerEngine.kt`: чистый класс (инжектится синглтоном) — StateFlow `RestTimerState(totalSec, remainingSec, running)`, методы `start(sec)`, `addSeconds(±15)`, `skip`, тик на корутине; по нулю — колбэк.
*   `service/WorkoutSessionService.kt`: foreground service (type `specialUse` в манифесте + permission `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS` c runtime-запросом), стартует при начале тренировки, стопается при завершении/отмене; уведомление: название тренировки, время, текущее упражнение; при активном отдыхе — отсчёт (обновление раз в сек через `setOnlyAlertOnce`), actions «+15 сек» и «Пропустить» (PendingIntent → broadcast → engine); по нулю — звук (RingtoneManager notification) + вибрация с учётом настроек.
*   Автостарт: `toggleSetCompleted(true)` во ViewModel → `RestTimerEngine.start(restSeconds упражнения ?: default из настроек)`.
*   На экране тренировки — пилюля таймера с градиентом: `−15с | ⏱ M:SS | +15с`, тап по центру — пропустить.
*   NotificationChannel `workout_session` (LOW для сессии) и `rest_timer` (HIGH для сигнала).

**Документация:**

*   https://developer.android.com/develop/background-work/services/fgs (foreground service types, API 36)

**Команды проверки:**

*   `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`

### Стадия 14: Тесты таймера

**Что добавить/реализовать:**

*   `RestTimerEngineTest` (coroutines-test, virtual time): отсчёт до нуля с колбэком, `addSeconds` продлевает, `skip` останавливает и зовёт колбэк один раз, повторный `start` перезапускает.

**Файлы:**

*   `app/src/test/java/com/valerochka1337/valerochkagym/service/RestTimerEngineTest.kt`

**Команды проверки:**

*   `./gradlew :app:testDebugUnitTest`

### Стадия 15: История

**Что добавить/реализовать:**

*   `ui/history/HistoryViewModel.kt` + боевой `HistoryScreen.kt`: LazyColumn завершённых тренировок (дата, название, длительность, объём, бейдж статуса выгрузки: Ожидает/Выгружено/Ошибка), пустое состояние.
*   `ui/history/WorkoutDetailScreen.kt` + ViewModel: все упражнения и подходы (значения по типу), заметка, кнопка «Повторить выгрузку» при FAILED (заглушка до Стадии 19), удаление тренировки с подтверждением.

**Команды проверки:**

*   `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`

### Стадия 16: Вход Google и настройки

**Что добавить/реализовать:**

*   `data/google/GoogleAuthManager.kt`: вход через Credential Manager (`GetGoogleIdOption`, серверный client ID из `strings.xml`-плейсхолдера) → email в SettingsRepository; авторизация scopes `spreadsheets` + `calendar.events` через `AuthorizationClient` (`AuthorizationRequest`), `suspend fun getAccessToken(): String` (запрос/обновление токена; проброс `UserRecoverableAuthException`-интента наружу для повторного согласия); `signOut()`.
*   Боевой `SettingsScreen.kt` + ViewModel: секция Google (войти/выйти, email), поле «Таблица» — принимает полный URL или ID (парсер `spreadsheetIdFrom(input)`: вырезает `/d/<id>/`), отдых по умолчанию (степпер 15 сек), свитчи звук/вибрация, кнопка «Выгрузить всё» (заглушка до Стадии 19).
*   `README.md`: пошаговая инструкция создания OAuth client ID (Android + Web client для idToken) в Google Cloud Console, включение Sheets API и Calendar API, получение SHA-1 (`./gradlew signingReport`).

**Документация:**

*   https://developer.android.com/identity/sign-in/credential-manager-siwg
*   https://developers.google.com/identity/authorization/android

**Команды проверки:**

*   `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`

### Стадия 17: Маппинг тренировки в строки Sheets

**Что добавить/реализовать:**

*   `domain/WorkoutRowMapper.kt`: `fun rows(workout: WorkoutFull, exercises: Map<Long, ExerciseEntity>): List<List<Any?>>` — только выполненные подходы, колонки строго: `workout_id, date (yyyy-MM-dd), start_time (HH:mm), workout_name, exercise, muscle_group, type, set_index, weight_kg, reps, duration_sec, speed_kmh, incline_pct, volume`; volume = weight×reps для STRENGTH, пусто иначе; локаль-независимые числа (точка).
*   `HEADER_ROW: List<String>` — те же колонки, константа рядом.

**Команды проверки:**

*   `./gradlew :app:assembleDebug`

### Стадия 18: Тесты маппера

**Что добавить/реализовать:**

*   `WorkoutRowMapperTest`: силовое/на время/бег дают правильные колонки, невыполненные подходы отброшены, volume считается только для STRENGTH, порядок колонок совпадает с HEADER_ROW.

**Файлы:**

*   `app/src/test/java/com/valerochka1337/valerochkagym/domain/WorkoutRowMapperTest.kt`

**Команды проверки:**

*   `./gradlew :app:testDebugUnitTest`

### Стадия 19: Выгрузка в Sheets (WorkManager)

**Что добавить/реализовать:**

*   `data/google/SheetsApi.kt` (Retrofit, base `https://sheets.googleapis.com/`): `GET v4/spreadsheets/{id}` (список листов), `POST .../batchUpdate` (addSheet «Workouts»), `GET .../values/Workouts!A:A` (существующие workout_id), `POST .../values/Workouts!A1:append?valueInputOption=RAW` (тело `{"values": [[...]]}`); DTO на kotlinx-serialization; OkHttp-интерцептор Bearer из `GoogleAuthManager.getAccessToken()`.
*   `data/google/SheetsRepository.kt` (интерфейс + impl): `uploadWorkout(workoutId)`: ensure лист «Workouts» + HEADER_ROW → прочитать колонку A → если UUID уже есть, вернуть Success (идемпотентность) → иначе append всех строк одним запросом; классифицировать ошибки (нет сети → retry, 401/403/404 → fail с причиной).
*   `worker/UploadWorkoutWorker.kt`: `@HiltWorker` CoroutineWorker; input `workoutId`; статусы: успех → UPLOADED, retryable → `Result.retry()` (backoff EXPONENTIAL 30 сек), fatal → FAILED + uploadError. Constraint NetworkType.CONNECTED. Конфигурация Hilt+WorkManager в `GymApplication` (`Configuration.Provider`).
*   Подключить: завершение тренировки (WorkoutSummaryViewModel) → enqueue (unique work `upload_<workoutId>`); «Повторить выгрузку» в деталях истории; «Выгрузить всё» в настройках (enqueue всех PENDING/FAILED). Если spreadsheetId/вход не настроены — воркер завершает FAILED с текстом «Настройте таблицу», в настройках подсказка.

**Документация:**

*   https://developers.google.com/sheets/api/reference/rest

**Команды проверки:**

*   `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`

### Стадия 20: Тесты выгрузки

**Что добавить/реализовать:**

*   `SheetsRepositoryTest` с фейковым `SheetsApi`: лист создаётся при отсутствии, повторная выгрузка того же UUID не дублирует строки, 403 → fatal-ошибка с причиной, IOException → retryable.

**Файлы:**

*   `app/src/test/java/com/valerochka1337/valerochkagym/data/SheetsRepositoryTest.kt`

**Команды проверки:**

*   `./gradlew :app:testDebugUnitTest`

### Стадия 21: Google Calendar (планирование)

**Что добавить/реализовать:**

*   `data/google/CalendarApi.kt` (Retrofit, base `https://www.googleapis.com/calendar/v3/`): `POST calendars/primary/events` (summary «Тренировка: {name}», start/end ISO, reminder popup 30 мин, длительность 1 ч), `DELETE calendars/primary/events/{eventId}`.
*   `data/google/CalendarRepository.kt`: `schedule(routineId, dateTime)` — insert события + запись `ScheduledWorkoutEntity` (транзакционно: при ошибке API ничего не сохраняем, ошибку показываем сразу); `cancel(scheduledId)` — удалить событие и запись (404 от API считать успехом удаления).
*   UI: пункт «Запланировать» в меню карточки программы → M3 DatePicker + TimePicker → schedule; блок «Ближайшие» вверху WorkoutsScreen (дата/время + название программы, наступившие подсвечены, тап → запуск программы + удаление записи, меню → отменить).

**Документация:**

*   https://developers.google.com/calendar/api/v3/reference/events/insert

**Команды проверки:**

*   `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`

### Стадия 22: Тесты календаря

**Что добавить/реализовать:**

*   `CalendarRepositoryTest` с фейковым API: успешный schedule сохраняет запись с eventId, ошибка API не оставляет локальной записи, cancel при 404 удаляет локальную запись.

**Файлы:**

*   `app/src/test/java/com/valerochka1337/valerochkagym/data/CalendarRepositoryTest.kt`

**Команды проверки:**

*   `./gradlew :app:testDebugUnitTest`

### Стадия 23: Полировка и финальная проверка

**Что добавить/реализовать:**

*   Expressive-моменты: анимация «Подход выполнен» (scale+glow), конфетти/акцент на PR в итоге, анимированные переходы навигации.
*   Пустые состояния всех списков (текст + иконка в стиле темы), запрос `POST_NOTIFICATIONS` при первом запуске тренировки.
*   Прогон всех тестов, ручная проверка сценария: создать программу → запустить → отметить подходы (таймер в фоне) → завершить → «Обновить программу?» → история → выгрузка.

**Команды проверки:**

*   `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`

## Заметки для исполнителя

*   После каждой стадии — коммит `feat: Стадия N — <название>`.
*   НЕ применять плагин `org.jetbrains.kotlin.android` (AGP 9 built-in Kotlin, будет конфликт «extension already registered»).
*   Hilt строго `2.59.2`; material3 пином `1.5.0-alpha24` поверх BOM `2026.06.00`.
*   Реальные вызовы Google API руками не проверяются в тестах — только фейки; живая проверка Sheets/Calendar требует настроенного OAuth client ID (README, Стадия 16) и выполняется вручную владельцем.
