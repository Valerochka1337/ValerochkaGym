# ValerochkaGym — архитектура

Однопользовательское Android-приложение для силовых тренировок. Один Gradle-модуль `:app`,
пакеты по слоям. Android 16+ (minSdk 36, targetSdk 37), Kotlin + Jetpack Compose
(Material 3 Expressive), Hilt, Room, DataStore, WorkManager, Retrofit.

## Слои

```
ui/        экраны (Compose) + ViewModel'и; theme/ (цвет, форма, типографика, GymMotion),
           haptics/ (GymHaptics), components/ (GymCard, PillButton, GymFilterChip, …)
domain/    use case'ы и чистая логика (CompleteSetUseCase, WorkoutRowParser,
           analysis/AnalyticsEngine — вся математика вкладки «Анализы»)
data/      Room (db/), Google-интеграция (google/), настройки (settings/),
           резервные операции (backup/), иконка лаунчера (appicon/)
service/   WorkoutSessionService (foreground) + RestTimerEngine
worker/    UploadWorkoutWorker + UploadScheduler, UploadMeasurementWorker + MeasurementUploadScheduler (WorkManager)
di/        Hilt-модули (Data, Domain, Google, Network) и квалификаторы
           (@ApplicationScope, @ComputeDispatcher)
```

## Потоки данных

- **Room → Flow → ViewModel → Compose.** ViewModel'и собирают состояние `combine(...)` +
  `stateIn(viewModelScope, WhileSubscribed(5000), initial)`; nullable-списки отличают
  «не загружено» от «пусто», one-shot события идут через `Channel(BUFFERED).receiveAsFlow()`.
- **Порядок упражнений активной сессии.** `WorkoutExerciseEntity.position` — персистентный порядок
  только текущей тренировки. Пока пользователь тянет grip, экран держит локальный список id и
  меняет его синхронно на каждом crossing; после отпускания репозиторий одной транзакцией
  проверяет полный уникальный набор id и перенумеровывает позиции. Исходная программа не меняется;
  её может обновить только подтверждение по факту в итогах тренировки.
- **Тяжёлые вычисления** (пересчёт аналитики) уводятся с Main через
  `flowOn(@ComputeDispatcher)` — в тестах квалификатор подменяется тестовым диспетчером.
- **Правки подходов** идут через единственный процессный писатель `WorkoutSetMutator`
  (канал + один потребитель): экран и кнопки уведомления пишут в одну очередь, поэтому
  быстрые тапы не теряют обновления. Закрытие подхода — общий `CompleteSetUseCase`
  (отметка в БД → резолв длительности → старт таймера; гейтится настройкой `rest_autostart`).

## База данных

Room v4, схемы коммитятся в `app/schemas/`, миграции только рукописные
(`MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4`) — `fallbackToDestructiveMigration` запрещён.
Замеры тела лежат отдельно в `body_measurements`: все показатели nullable (пропуск не равен нулю),
а масса жира и WHR при отсутствии явного InBody-значения вычисляются из сохранённых показателей.
Посев встроенного каталога упражнений идемпотентен и живёт в `onOpen`
(`GymDatabaseCallback`); карты мышц досеиваются `ExerciseMuscleSeeder`-ом.
Экспорт базы (`DatabaseExporter`) делает `wal_checkpoint(TRUNCATE)` и копирует `gym.db`
в выбранный через SAF документ; очистка (`ClearDataUseCase`) стирает таблицы, пересеивает
каталог и отменяет очередь WorkManager, не трогая настройки.

## Фоновые механизмы

- **WorkoutSessionService** — foreground-сервис активной тренировки: одно promoted-ongoing
  уведомление (Live Updates, Android 16) в двух состояниях (рабочий подход / отдых) с
  контекстными действиями и инлайн-правкой «60x8». Отсчёт в чипе рисует система
  (`setChronometerCountDown` + дедлайн по стенным часам), поэтому `RestTimerEngine` хранит
  дедлайн, а не остаток. `START_NOT_STICKY`: после смерти процесса сессию не восстановить,
  и «зомби»-уведомление хуже отсутствия (осознанное ограничение).
- **RestTimerEngine** — чистый Kotlin с инжектируемыми `WallClock` и `CoroutineScope`
  (в тестах — виртуальное время); тики пересчитываются от дедлайна, поэтому заморозка
  процесса (Doze) самокорректируется.
- **UploadWorkoutWorker** — выгрузка тренировки в Google Sheets: уникальная работа
  `upload_<id>` (REPLACE), сеть обязательна, экспоненциальный backoff, 5 попыток; на последней
  транзиентной ошибке статус становится FAILED с причиной для UI.
- **UploadMeasurementWorker** — та же политика для одного замера (`upload_measurement_<id>`).
  Экспорт замеров append-only: локальные правки и удаление не переписывают уже добавленную строку.

## Google-интеграция

Вход — Credential Manager; scopes (Sheets + Calendar) — GMS `AuthorizationClient`;
`serverClientId` — **Web** client ID из `strings.xml`. HTTP — Retrofit + kotlinx-serialization
(второй инстанс за `@Named("calendar")`). Ошибки классифицируются общим
`HttpErrorClassifier` на постоянные (401/403/404/4xx — нужен пользователь) и временные
(429/5xx/сеть — ретрай), формулировки одинаковы для выгрузки и импорта. Импорт истории
(`WorkoutImportRepository`) дедупит по `workout_id`, матчит упражнения по имени
(Unicode-aware lowercase), создаёт недостающие вместе с картой мышц и считает
нераспознанные строки (`skippedRows` всплывает в снэкбар). Лист `Measurements` создаётся после
`Workouts`, если его ещё нет; UUID `measurement_id` в первой колонке гарантирует идемпотентность
повторных append-запросов.

## Осознанные решения

| Решение | Почему |
|---|---|
| Zero-logging (нет `Log.*`) | все ошибки синка видны в UI (UploadStatusBadge, ImportResult, снэкбары); логи в однопользовательском приложении некому читать |
| Predictive back — только системный | seekable-переходы Navigation Compose не подключены; пружинные слайды играют как обычные pop'ы |
| Бэкап включён целиком | в `gym.db` и DataStore нет токенов (авторизация в Credential Manager); история переживает переустановку |
| Строки UI захардкожены в Kotlin | приложение одноязычное и личное; `strings.xml` держит только `app_name` и OAuth client ID |
| Dark-only, 4 акцента через activity-alias | Android не перекрашивает иконку — `AppIconManager` включает alias выбранного акцента, дождавшись ухода в фон (иначе система снесёт задачу) |
| Сервис не переживает смерть процесса | таймер in-memory; восстановление потребовало бы персистить сессию ради редкого случая |

## Тесты

`./gradlew :app:testDebugUnitTest` — 430+ JUnit4-тестов: чистая логика и ViewModel'и — на
рукописных фейках (без мок-библиотек), DAO и миграции — Robolectric + in-memory Room
(`RoomDaoTest`), графики и карта тела — рендер-смоук `AnalysisRenderTest` (Robolectric,
нативная графика, снимки в `app/build/reports/analysis-render/`). Robolectric закреплён на
sdk=36 (для 37 нет jar), тестовый JVM — JDK 21 (см. `app/build.gradle.kts`).
