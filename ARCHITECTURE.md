# ValerochkaGym — архитектура

Однопользовательское Android-приложение для силовых тренировок. Один Gradle-модуль `:app`,
пакеты по слоям. Android 16+ (minSdk 36, targetSdk 37), Kotlin + Jetpack Compose
(Material 3 Expressive), Hilt, Room, DataStore, WorkManager, Retrofit.

## Слои

```
ui/        экраны (Compose) + ViewModel'и; exercise/ (карточка упражнения и статистика),
           gyms/ (каталог залов и их упражнений),
           theme/ (Material You, light/dark схемы, форма, типографика, GymMotion), haptics/ (GymHaptics),
           components/ (GymCard, PillButton, GymFilterChip, …)
domain/    use case'ы и чистая логика (CompleteSetUseCase, WorkoutRowParser,
           ExerciseStatisticsCalculator, analysis/AnalyticsEngine — математика аналитики)
data/      Room (db/), Google-интеграция (google/), AI-генератор (ai/), настройки
           и зашифрованный ключ (settings/), резервные операции (backup/), иконка лаунчера (appicon/)
service/   WorkoutSessionService (foreground) + RestTimerEngine
worker/    UploadWorkoutWorker + UploadScheduler, UploadMeasurementWorker + MeasurementUploadScheduler,
           UploadRoutineWorker + RoutineUploadScheduler, UploadConfigurationWorker (WorkManager)
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
- **Ограничение по залам.** Программа хранит 0..N связей `RoutineGymEntity`; пустой набор означает
  полный каталог, непустой — пересечение `GymExerciseEntity` по всем выбранным залам. Сохранение
  и старт повторно проверяют инвариант и возвращают список конфликтных упражнений, ничего не
  удаляя. При старте связи копируются в `WorkoutGymEntity`: активный picker использует стабильный
  снимок, а история тренировок его не показывает.
- **Тяжёлые вычисления** (пересчёт аналитики) уводятся с Main через
  `flowOn(@ComputeDispatcher)` — в тестах квалификатор подменяется тестовым диспетчером.
- **Карточка упражнения.** Маршрут `exercise_detail/{exerciseId}` открывается из активной сессии,
  редактора программы, истории, итогов, аналитики и по info-кнопке библиотечного пикера.
  `ExerciseDetailViewModel` реактивно объединяет каталог, карту мышц и плоский поток завершённых
  подходов `WorkoutDao.observeCompletedSets()`. `ExerciseStatisticsCalculator` на compute-диспетчере
  группирует подходы по тренировкам: e1RM для силовых, суммарное время для timed и расчётную
  дистанцию для cardio. Активная незавершённая тренировка в карточную статистику не попадает.
- **Правки подходов** идут через единственный процессный писатель `WorkoutSetMutator`
  (канал + один потребитель): экран и кнопки уведомления пишут в одну очередь, поэтому
  быстрые тапы не теряют обновления. Закрытие подхода — общий `CompleteSetUseCase`
  (отметка в БД → резолв длительности → старт таймера; гейтится настройкой `rest_autostart`).
- **ИИ-черновики.** Библиотека берёт снимок всех упражнений и их карт мышц, отправляет его вместе
  с описанием в OpenAI-совместимый API и принимает только строгий JSON. Ответ валидируется локально: существующий
  ID обязан быть в снимке, а новая карта использует известные мышцы, уникальные веса 5–100 с шагом
  5 и хотя бы одну целевую мышцу 100%. Импорт InBody работает так же: `InBodyPhotoEncoder` локально
  исправляет EXIF-поворот, уменьшает и перекодирует выбранное фото в JPEG data URL, а
  `AiApiInBodyReportAiReader` принимает только строгий отчёт с пятью уникальными сегментами,
  конечными неотрицательными числами и валидной датой. Оба сценария создают только редактируемый
  черновик; Room меняется после явного сохранения пользователем.

## База данных

Room v12, схемы коммитятся в `app/schemas/`, миграции только рукописные
(`MIGRATION_1_2` … `MIGRATION_11_12`) — `fallbackToDestructiveMigration` запрещён. Замеры тела лежат отдельно в `body_measurements`: все
показатели nullable (пропуск не равен нулю), а масса жира и WHR при отсутствии явного
InBody-значения вычисляются из сохранённых показателей. В v5 добавлены фактические поля полного
отчёта и фиксированные nullable-поля пяти сегментов; ID, пол, возраст, рост, цели контроля веса,
калории упражнений и импеданс в модель не входят.
В v7 у программ появились независимые от SQLite auto-ID `syncId` и монотонный `updatedAt`: они
связывают снимки пользовательской программы между установками и разрешают конфликт по более новой
версии.
В v8 такие же переносимые идентификаторы получили упражнения; встроенные UUID детерминированы
названием. Добавлены `gyms`, `gym_exercises`, `routine_gyms` и `workout_gyms`. UI работает через
`GymRepository`, поэтому будущий серверный транспорт не меняет экраны и доменные правила.
`configuration_tombstones` служит durable outbox удаления залов и программ: запись создаётся
одной транзакцией с удалением и исчезает только после подтверждённой выгрузки.
Посев встроенного каталога упражнений идемпотентен и живёт в `onOpen`
(`GymDatabaseCallback`); карты мышц досеиваются `ExerciseMuscleSeeder`-ом.
Экспорт базы (`DatabaseExporter`) делает `wal_checkpoint(TRUNCATE)` и копирует `gym.db`
в выбранный через SAF документ; очистка (`ClearDataUseCase`) стирает таблицы, пересеивает
каталог и отменяет очередь WorkManager, не трогая настройки.

В v10 `body_measurements` остаётся живой проекцией существующих ручных и InBody-замеров.
Неизменяемая история `measurement_snapshots` и `health_sync_outbox` с ключом
`(category, syncId, version)` создаются одной транзакцией с каждой правкой или tombstone.
Миграция 9→10 backfill-ит для каждой прежней строки канонический v1 snapshot и pending outbox;
поэтому включённая позднее синхронизация не теряет legacy-историю. Равные ID+version с разным
payload/hash никогда не разрешаются временем: обе версии остаются в `health_sync_conflicts` до
ручного решения.

«Здоровье» хранит расширяемые первичные агрегаты: `health_reports`, атомарные
`health_observations`, подтверждённые `health_restrictions` и local-only `health_documents`.
`HealthRepository` — единственная граница транзакций report/observation/restriction и их outbox;
`MeasurementRepository` — единственная граница live measurement, snapshot и outbox. Черновики
не попадают в Room. Документ сначала получает состояние PENDING, копируется в
`noBackupFilesDir/health_documents`, проверяется SHA-256 и атомарно становится READY; startup
cleanup удаляет dangling PENDING строки и временные файлы. Только READY-документ допустим в
архив; originals исключены из backup и device transfer.

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
- **UploadMeasurementWorker** — bridge для прежних `upload_measurement_<id>` задач: до auth/network
  проверяет включённость категории Measurements и подавляется при её выключении. Новая выгрузка
  идёт по durable outbox/version со стабильным именем `category:syncId:version` (REPLACE),
  поэтому ACK удаляет только точную строку; ошибка, отмена или потерянный ответ оставляют её
  pending, а re-enable ставит pending записи снова.
- **HealthSyncWorker** — аналогичная category-scoped очередь для reports/observations и
  restrictions: стабильное имя `category:syncId:version` (REPLACE), network constraint,
  bounded retry, idempotency key `syncId:version`, отдельные WorkManager tags и отмена только
  нужной категории; startup reconciliation снова ставит pending записи.
- **UploadRoutineWorker** — выгрузка снимка пользовательской программы (`upload_routine_<UUID>`)
  либо tombstone удаления. Каждый снимок содержит UUID и версию; уникальная работа заменяет
  устаревшую очередь, а лист `Routines` дедуплицирует уже добавленную версию. Тем же запуском
  выгружается снимок её залов в `RoutineGyms`. Незавершённые tombstone остаются в Room и снова
  ставятся в очередь действием «Выгрузить всё».
- **UploadConfigurationWorker** — снимки упражнений и залов либо tombstone зала. Уникальные
  работы используют стабильный UUID; создание упражнения из ограниченного picker одной
  транзакцией сохраняет каталог, мышцы, связи залов и (для активной сессии) строку тренировки.
- **Обновление приложения** не использует периодический worker: release-сборка один раз за
  запуск процесса запрашивает публичный `GitHub Releases /latest`, а из настроек проверку можно
  повторить вручную. `AppUpdateViewModel` общий для оболочки и экрана настроек, поэтому
  скачивание и прогресс не теряются при навигации. Выбор «Позже» живёт до смерти процесса;
  «Не напоминать» сохраняет тег в DataStore и не скрывает следующий тег.
- **Восстановление недельного расписания** — уникальная цепочка
  `weekly_schedule_recovery` без network constraint и с `APPEND_OR_REPLACE`: worker может офлайн
  завершить локальный terminal marker, а при реально нужной сети возвращает retry. Успешная
  авторизация в настройках вызывает отдельный `wake()` с `REPLACE`, чтобы recovery продолжился
  сразу, а не ждал прежний exponential backoff. Активный шаблон остаётся SSOT в
  `settings.preferences_pb`, а отдельный machine-local journal хранит фазу `CREATE_NEW`,
  `CLEANUP_NEW` или `DELETE_OLD`. Новые Calendar event ID заранее генерируются как 32 lowercase
  hex и повторяются после смерти процесса; insert 409 и delete 404/410 подтверждают
  идемпотентный шаг. Terminal marker сначала локально применяет целевой active snapshot и только
  затем очищает journal. Один singleton repository `Mutex` сериализует UI и worker. Journal
  исключён из backup/device transfer, а active хранит nullable `ownerEmail`; legacy owner adoption
  остаётся эвристикой, после которой OAuth token всегда запрашивается для конкретного аккаунта.

## Здоровье: UI и навигация

`AnalysisScreen` сохраняет три верхние вкладки приложения и добавляет четвёртую внутреннюю секцию
«Здоровье». На compact и при fontScale 2 selector — горизонтально прокручиваемый ряд chip с
минимальной высотой 48dp, семантикой selected/state; на medium/expanded ряд остаётся без
обрезания. `AnalysisViewModel` объединяет Room flows body measurements, live reports и live
restrictions; выбранный Analysis period фильтрует историю, но не активные ограничения.

Health section показывает отдельные loading, empty, body-only, content, offline, conflict и
missing-original состояния. Карточка тела и прежнее действие в app bar ведут на тот же
`MEASUREMENTS` route — запись не копируется. Pushed routes `health_editor`,
`health_restriction_editor`, `health_archive` и `health_report/{syncId}` получают system Up;
period для report detail передаётся аргументами. Editor state — immutable ViewModel state,
одноразовое завершение — buffered Channel; выбранная секция/period и минимальные route arguments
переживают recreation, а подтверждённый доменный результат остаётся только в Room.

## Обновление APK

`GitHubAppUpdateRepository` принимает только stable SemVer-тег и asset с детерминированным именем
`ValerochkaGym-v<versionName>.apk`. Потоковое скачивание идёт в `cache/app_updates`, ограничено
100 МБ и завершается только при совпадении размера и SHA-256 из GitHub API. Затем
`AndroidAppUpdateInstaller` проверяет package name, `versionName`, строго больший `versionCode` и
криптографически подтверждённую подпись APK против подписи установленного приложения. Лишь после
этого байты private-файла записываются прямо в `PackageInstaller.Session`: наружу не публикуется
ни файл, ни `content://` URI. Сессия помечается как пользовательская установка скачанного файла,
содержит исходные GitHub URL и всегда требует явного подтверждения. `Session.commit()` возвращает
статусы через приватный mutable `PendingIntent`; `STATUS_PENDING_USER_ACTION` передаёт системный
экран подтверждения, а блокировки и ошибки преобразуются в безопасный для UI текст. Такой путь не
зависит от передачи URI-grant между внутренними компонентами установщика ColorOS/OxygenOS.

Android требует специальный доступ «Установка неизвестных приложений» именно для ValerochkaGym:
если его нет, UI открывает `ACTION_MANAGE_UNKNOWN_APP_SOURCES`, а после возврата продолжает
установку. Сама установка всегда остаётся явным системным подтверждением пользователя.

Workflow на `main` собирает APK постоянным release-ключом, запускает тесты, проверяет `apksigner`
и создаёт `v<versionName>` только при отсутствии такого GitHub Release. Поэтому обычный коммит без
увеличения версии не становится обновлением, а уже опубликованный бинарник не перезаписывается.

## Google-интеграция

Вход — Credential Manager; scopes (Sheets + Calendar) — GMS `AuthorizationClient`;
`serverClientId` — **Web** client ID из `strings.xml`. HTTP — Retrofit + kotlinx-serialization
(второй инстанс за `@Named("calendar")`). Ошибки классифицируются общим
`HttpErrorClassifier` на постоянные (401/403/404/4xx — нужен пользователь) и временные
(429/5xx/сеть — ретрай), формулировки одинаковы для выгрузки и импорта. `WorkoutImportRepository`
читает все app-managed листы. `Workouts` дедупятся по `workout_id`; их legacy-упражнения по-прежнему
матчатся по имени. Отдельный append-only `Exercises` сохраняет стабильный UUID, профиль и полную
карту мышц; `Gyms` — версионированный состав зала; `RoutineGyms` — набор залов версии программы.
Импорт применяет их в порядке упражнения → снимки залов → программы/связи → удаления залов →
тренировки. Перед commit он повторно проверяет весь aggregate программ и активной тренировки;
конфликт откатывает транзакцию целиком. Отсутствие новых листов означает старую таблицу без
ограничений по залам.
`Measurements` — версионированные append-only snapshots в управляемом префиксе A:AY: A:AP —
legacy-данные, AQ:AU — `version`, `updated_at`, `is_deleted`, `payload_hash`,
`idempotency_key`, а AV:AY — условия. Импорт по-прежнему принимает legacy A:AP и промежуточный
A:AU; перед записью репозиторий повторно читает и безопасно обновляет заголовок, сохраняя
user-owned колонки справа. Tombstone убирает только текущую локальную проекцию. `Routines` хранит append-only снимки программ: формат
A:L содержит стабильный `exercise_id`, а legacy A:K по-прежнему читается по имени. Импорт берёт
для каждого UUID максимальный `updated_at`, создаёт недостающие custom-упражнения и применяет
tombstone удаления. После успешного входа импорт запускается автоматически, если ID таблицы уже
восстановлен через Android Backup/DataStore; иначе ID нужно один раз вставить в настройках.
Лист `Measurements` создаётся после `Workouts`, если его ещё нет. Настройки объединяют четыре
независимые opt-in категории: workouts+configuration, Measurements/InBody,
reports/observations и restrictions. У прежнего подключённого документа однократная миграция
инициализирует только workouts и Measurements; у нового подключения все категории выключены.
Category disable отменяет лишь tagged work и не удаляет локальные или remote записи. Отдельное
подтверждённое remote clear ставит нужные workers на паузу/отменяет их и использует Sheets
clear-values; для Measurements очищается только managed `A2:AY`, после safe header upgrade,
без пользовательских колонок и других категорий. Производные Analysis-метрики не синхронизируются.

`HealthReports`, `HealthObservations` и `HealthRestrictions` импортируются только как строгие
первичные агрегаты с зафиксированными заголовками `HealthSheetRows`: отсутствующий лист совместим,
а дубликат, orphan, расходящаяся версия или некорректные enum/date/status-отношения отклоняют
весь импорт до транзакции. Managed prefix листа не затрагивает следующие пользовательские колонки;
черновики, derived Analysis-данные и локальный `originalText` в Sheets не попадают. Конфликт всегда
хранит проверяемый SHA-256 payload: при отсутствии remote `payload_hash` он вычисляется из
канонического совместимого payload до передачи в resolver.

## Интеграция с нейросетью

`AiApiExerciseAiGenerator` вызывает OpenAI-совместимый `chat/completions` через отдельный
Retrofit-инстанс. Пользовательский HTTP(S)-адрес нормализуется до base URL с завершающим `/`;
credentials в URL, query и fragment отклоняются. Если путь ещё не заканчивается на `/v1`, этот
API-префикс добавляется автоматически; существующий `/v1` не дублируется. Retrofit получает каждый
полный адрес через `@Url`, поэтому сохраняется и reverse-proxy prefix перед `/v1`.
`usesCleartextTraffic=true` разрешает локальные HTTP-развёртывания; в этом режиме Bearer key,
описания и фото передаются без транспортного шифрования.

Настройки запрашивают с сохранённым Bearer key авторизованный каталог `GET /v1/models`. Ответ
содержит ID и, если доступен, владельца, но не гарантирует метаданные возможностей, поэтому
пользователь явно выбирает chat-модель с поддержкой изображений. Выбранный ID и base URL лежат в обычном
DataStore; модель общая для генерации упражнений и распознавания InBody. Готовой ИИ-конфигурация
считается только при наличии адреса, ключа и модели. Смена сервера сбрасывает выбор модели.

Оба сценария отправляют нестриминговый `POST /v1/chat/completions` с `response_format=json_object`.
Это не привязывает разные upstream-модели к поддержке strict structured outputs: точная JSON
Schema вкладывается в системную инструкцию, а состав и значения ответа всё равно валидируются
локально. Схема упражнения имеет фиксированную форму: все поля обязательны, поля невыбранной ветки
равны `null`, поэтому ответ `kind=existing` содержит ID записи. Шторка ждёт атомарный JSON и либо
открывает существующее упражнение, либо передаёт новый черновик в `ExerciseEditorSheet`; валидация
проверяет ветку `kind`, известный ID и карту мышц, но терпимо игнорирует неиспользуемые поля другой
ветки. Ошибки авторизации, квоты, rate limit, timeout и недоступной модели переводятся в сообщения
UI; в последнем случае обе ИИ-формы предлагают открыть выбор модели.

Для `chat/completions` используется отдельный `OkHttpClient`: connect 20 с, write 2 минуты,
read 5 минут и общий call timeout 6 минут. Google API сохраняют стандартные короткие таймауты,
а каталог моделей дополнительно ограничен 12 секундами на уровне `SettingsViewModel`.

Та же выбранная модель читает фото InBody и health PDF/photo через multimodal message
(инструкция + bounded JPEG data URL)
и отдельную schema в системной инструкции. В запрос попадает только
выбранный снимок, а извлекаются только
фактические показатели отчёта и пять сегментов. Снимок камеры лежит во временном cache-файле ровно
до обработки и затем удаляется; снимок из системного Photo Picker не копируется в приложение.
Ни фото, ни ID/пол/возраст/рост, цели,
калории упражнений или импеданс не сохраняются.

Health readers принимают только строгий JSON и создают редактируемый UI-черновик; Room, архив и
Sheets меняются только после явного сохранения. Перед каждой отправкой UI показывает domain и
model; public HTTP для medical data отклоняется, loopback HTTP требует отдельного видимого
подтверждения. PDF render/parse bounded и cancellation-aware на compute dispatcher, OCR нет.
`AiResponseLogger` — no-op и в debug: document, request, full response и error body не попадают
в Logcat.

API key живёт в отдельном `ai_secrets.preferences_pb`: полный ключ хранится как AES/GCM-шифротекст,
а рядом лежит безопасное превью `sk-************1234` для настроек. Ключ шифрования
неэкспортируемо хранится в Android Keystore. Файл исключён из cloud backup и device transfer,
поэтому после переустановки или смены устройства key вводится заново.

## Осознанные решения

| Решение | Почему |
|---|---|
| Zero-logging AI/health | ошибки синка видны в UI (UploadStatusBadge, ImportResult, снэкбары); `AiResponseLogger` не пишет document, request, response, error body или throwable и в debug |
| Predictive back — только системный | seekable-переходы Navigation Compose не подключены; пружинные слайды играют как обычные pop'ы |
| Бэкап включён почти целиком | история и обычные настройки переживают переустановку; отдельный зашифрованный API key исключён из бэкапа и переноса |
| Строки UI захардкожены в Kotlin | приложение одноязычное и личное; `strings.xml` держит только `app_name` и OAuth client ID |
| Material You + 4 фирменные палитры | Системная dynamic palette и System/Light/Dark — значения по умолчанию; фирменная палитра также выбирает launcher alias через `AppIconManager` после ухода приложения в фон |
| Адаптивная оболочка | `NavigationSuiteScaffold` выбирает navigation bar/rail по окну, а экранный контент центрируется и ограничивается 960 dp |
| Сервис не переживает смерть процесса | таймер in-memory; восстановление потребовало бы персистить сессию ради редкого случая |

## Тесты

`./gradlew :app:testDebugUnitTest` — 600+ JUnit4-тестов: чистая логика и ViewModel'и — на
рукописных фейках (без мок-библиотек), DAO и миграции — Robolectric + in-memory Room
(`RoomDaoTest`). Расчёт карточной статистики и её реактивное редактирование покрыты
`ExerciseStatisticsCalculatorTest` и `ExerciseDetailViewModelTest`; графики и карта тела —
рендер-смоук `AnalysisRenderTest` (Robolectric, нативная графика, снимки в
`app/build/reports/analysis-render/`, включая `exercise-detail.png`). Robolectric закреплён на
sdk=36 (для 37 нет jar), тестовый JVM — JDK 21 (см. `app/build.gradle.kts`).
