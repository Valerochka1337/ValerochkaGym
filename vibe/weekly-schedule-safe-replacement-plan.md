# Безопасная замена недельного расписания

Slug: `weekly-schedule-safe-replacement`
Issue: GitHub #8
Рабочая ветка: `fix/weekly-schedule-safe-replacement` от актуальной `main`
Базовая версия на момент планирования: `versionCode = 10`, `versionName = "1.3.2"`

## Цель

Заменить destructive-порядок `delete old -> insert new` в `WeeklyScheduleRepositoryImpl` на
durable двухфазную операцию. Старое активное расписание остаётся локальным SSOT и существует в
Google Calendar, пока все новые RRULE-события не подтверждены. Любой незавершённый insert/delete
должен быть идемпотентно восстановим после исключения, отмены корутины, смерти процесса или
повторного запуска приложения, без повторных вставок и потери event ID.

Та же машина состояний обслуживает `clear`: локальное расписание становится пустым только после
подтверждённого удаления всех его удалённых серий. Интерактивные Save/Clear и фоновое
восстановление проходят через один process-wide писатель.

## Scope

- durable JSON-журнал одной незавершённой операции в отдельном Preferences DataStore;
- подготовленный снимок всех запросов новой серии до первого сетевого вызова;
- клиентские Google Calendar event ID: ровно 32 символа lowercase hex;
- состояния `CREATE_NEW`, `CLEANUP_NEW`, `DELETE_OLD` и crash-safe terminal commit;
- повторяемая обработка insert `409` и delete `2xx/404/410`;
- уникальная WorkManager-задача с сетевым constraint, запуск при старте процесса и после
  отложенной ошибки;
- снимок email Google-аккаунта и пауза при несовпадении аккаунта;
- backward-compatible `ownerEmail` активного расписания и account-bound OAuth token;
- общий single-flight для Save/Clear в ViewModel и единый `Mutex` singleton-репозитория для
  UI/worker;
- typed fail-closed чтение journal (`Absent`/`Present`/`Unreadable`) и исключение только
  machine-local journal-файла из cloud backup/device transfer;
- разные пользовательские сообщения для сохранения, очистки и отложенного восстановления;
- unit/Robolectric regression-тесты, архитектурная документация и один patch-инкремент версии.

## Non-goals

- изменение поведения ad-hoc `CalendarRepository.schedule/cancel` или таблицы
  `scheduled_workouts`;
- Room entity/DAO/schema/migration;
- новый экран, маршрут, разрешение или dependency;
- периодический worker, foreground service или ручная кнопка управления журналом;
- изменение `DayRule` или смысла существующих rules/event ID; nullable `WeeklySchedule.ownerEmail`
  добавляется только для безопасной привязки уже существующих remote events к аккаунту;
- графики, render snapshots, скриншоты или видеозапись;
- миграция уже потерянных/осиротевших событий, созданных старой реализацией до этого исправления.

## Допущения и инварианты

1. Существующий `settings.preferences_pb` и ключ `weekly_schedule` остаются SSOT активного
   расписания, которое читают календарь и редактор. Journal никогда не показывается как активное
   расписание.
2. Journal заморожен как **отдельный** Preferences DataStore-файл
   `datastore/weekly_schedule_operations.preferences_pb` с одним JSON-ключом `pending_operation`.
   Это не второй ключ settings DataStore: отдельный файл позволяет исключить только journal из
   backup/transfer, оставив active в переносимом `settings.preferences_pb`.
3. Между active и journal DataStore общей атомарности нет и план её не обещает. Каждый JSON
   aggregate меняется атомарно только внутри своего файла. Terminal marker protocol компенсирует
   межфайловое окно: journal сначала durable фиксирует `DELETE_OLD` с пустым
   `pendingDeleteIds`, затем один атомарный settings edit заменяет весь active
   `weekly_schedule`, и лишь затем journal очищается. Replay marker до или после active commit
   повторяет тот же snapshot идемпотентно.
4. Для Save все имена программ резолвятся и все `CalendarEventDto` строятся до записи journal и до
   сети. Отсутствующая программа завершает запрос без journal/API/изменения active.
5. Каждому новому recurring request заранее назначается `id` из 16 криптографически случайных байт,
   закодированных в 32 lowercase hex. Один journal всегда повторяет тот же ID.
6. `CalendarEventDto.id` nullable и помечен `@EncodeDefault(NEVER)`: weekly request передаёт ID,
   ad-hoc request по-прежнему полностью опускает поле `id`.
7. В journal сохраняются operation kind (`REPLACE`/`CLEAR`), phase, normalized expected account email,
   old active snapshot, target active snapshot, подготовленные request snapshots,
   `pendingCreateIds`, `cleanupNewIds`, `pendingDeleteIds` и исходная причина ошибки cleanup.
   Коллекции уникальны и имеют детерминированный порядок.
8. Непосредственно перед каждым insert ID **добавляется** в durable `cleanupNewIds`, но остаётся в
   `pendingCreateIds`. Поэтому crash/cancellation между server apply и success checkpoint оставляет
   phase `CREATE_NEW`: recovery повторяет тот же ID, получает `409` и только тогда подтверждает
   insert. Пойманный именно как `IOException` исход считается failed interactive insert: repository
   durable переводит operation в `CLEANUP_NEW` и очищает attempted IDs, даже если server успел
   применить запрос до сетевого timeout. `CancellationException` не попадает в эту ветку. Лишь 2xx
   или `409` для того же request ID удаляет ID из `pendingCreateIds`; unattempted prepared IDs
   никогда не попадают в `cleanupNewIds`.
9. Пойманная HTTP/IO ошибка создания переводит journal в `CLEANUP_NEW` до попытки cleanup. Cleanup удаляет
   ровно `cleanupNewIds` (attempted/confirmed new), не все prepared IDs и не old IDs; old active
   локально и remote не трогается. Journal очищается только когда cleanup получил `2xx/404/410`
   для каждого cleanup-eligible ID.
10. Переход в `DELETE_OLD` допустим только когда `pendingCreateIds` пуст и тем самым подтверждены
    все prepared IDs. В этой фазе
    worker/UI больше не вызывает insert. Каждый old ID удаляется из `pendingDeleteIds` одним
    durable edit только после `2xx/404/410`.
11. Clear сразу создаёт journal kind `CLEAR`, phase `DELETE_OLD`, с пустым target и всеми old ID.
    Active становится пустым только после полного remote delete и terminal protocol.
    `save(WeeklySchedule())` нормализуется в тот же безопасный CLEAR-путь, а не создаёт особый
    пустой replace.
12. `WeeklyScheduleRepositoryImpl` остаётся `@Singleton` через `GoogleModule`; один приватный
    `Mutex` сериализует `save`, `clear` и `resumePendingOperation` в процессе. `CancellationException`
    всегда пробрасывается после уже сделанных durable checkpoint, не превращается в Failure.
13. `WeeklySchedule` получает `ownerEmail: String? = null`, поэтому старый JSON читается без
    миграции. Ownerless non-empty legacy active при первой authenticated Save/Clear атомарно
    принимает normalized текущий persisted Google email **до** token/API/journal. Это один
    `settingsDataStore.edit`: внутри того же Preferences snapshot читаются ключи `google_email`
    и `weekly_schedule`, повторно валидируется ownerless non-empty active и записывается owner;
    отдельный `SettingsRepository.settings.first()` для claim запрещён как check/edit race. Если
    email пуст — операция fail/paused без внешних вызовов. После adoption любое несовпадение
    current email с active/journal owner блокирует API; sign-out owner не стирает.
14. Weekly flow не полагается на раздельные «проверил settings email -> получил произвольный
    token» шаги. Рядом с `GoogleAuth` добавляется отдельный seam `AccountBoundGoogleAuth` с
    `getAccessTokenForAccount(expectedEmail)`; `GoogleAuthManager` реализует оба интерфейса и строит
    `AuthorizationRequest.Builder.setAccount(Account(expectedEmail, "com.google"))`. Поэтому
    returned token относится к expected owner даже если persisted email изменился между check и
    token completion. Existing no-argument auth contract для Sheets/ad-hoc не меняется.
    Интерактивный `GoogleAuth.authorize(activity)` также читает persisted `googleEmail` и при его
    наличии строит тот же request с `setAccount`; поэтому consent для восстановления owner A не
    может случайно выдаться B. `AccountBoundGoogleAuth.authorizeForAccount(activity,
    expectedEmail)` допустим как более явный эквивалент, если implementation path требует передать
    owner из weekly UI; оба пути обязаны иметь request-account test.
15. WorkManager имеет единственное имя `weekly_schedule_recovery`, policy
    `APPEND_OR_REPLACE`, не имеет network constraint и использует exponential backoff. Поэтому
    локальный terminal marker завершается офлайн, а реально сетевой шаг возвращает `Retry`. Текущий
    `androidx.work:work-runtime:2.11.2` проверен и содержит enum value; append исключает lost wakeup,
    если enqueue происходит после последнего journal read, но до finish текущего worker.
    Обычный enqueue не заменяется на `REPLACE`: это потребовало бы отдельного
    cancellation/replacement-контракта над durable journal. Явный `wake()` после успешной
    пользовательской авторизации использует
    `REPLACE`, чтобы отменить ожидающий backoff и немедленно повторить recovery. App-start и
    обычные durable continuation по-прежнему используют `APPEND_OR_REPLACE`, поэтому запуск
    процесса самим worker не отменяет текущую работу.
16. Ручная Save/Clear перед созданием новой операции сначала пытается завершить существующий
    journal. Пока он не завершён/не очищен, новая операция не создаётся.
17. Чтение journal возвращает sealed `Absent`, `Present(validOperation)`, `Unreadable(cause)`.
    JSON validation failure, DataStore `IOException` и corruption не преобразуются в Absent и не
    очищают файл: repository fail-closed, не создаёт новую operation и не вызывает Calendar API.
18. При `JournalRead.Absent` empty active (`rules.isEmpty()`) для `clear()` и
    `save(WeeklySchedule())` — немедленный идемпотентный Success без email/token/new journal/API.
    Present journal всегда сначала resume-ится (включая terminal marker после active commit), а
    Unreadable fail-closed. Для non-empty target при empty active email обязателен и owner
    target/journal задаётся до API.
19. Journal-файл исключается в `backup_rules.xml` и в обеих секциях
    `data_extraction_rules.xml`; active settings не исключается. Поэтому stale in-flight journal
    не восстанавливается/не исполняется на другом device, а перенесённый active с owner остаётся
    управляемым только account-bound token.
20. UI-строки остаются захардкоженными в Kotlin. Нет новых цветов, motion или haptics; обе кнопки
    сохраняют текущие компоненты, touch target и adaptive layout.
21. Local-only terminal precedence: `DELETE_OLD` с пустым `pendingDeleteIds` сначала применяет
    target active и очищает journal без чтения current email, token или account check. Owner
    mismatch/empty email гейтят только фазы с будущим Calendar API (`CREATE_NEW`, непустые
    `CLEANUP_NEW`/`DELETE_OLD`), иначе sign-out или смена аккаунта могли бы навсегда заблокировать
    уже подтверждённый terminal commit.

## Acceptance criteria

- **AC-001 — Безопасный insert failure.** Если хотя бы один новый insert не подтверждён, old
  active остаётся неизменным в DataStore, old Calendar event ID не удаляются, а UI сообщает, что
  старое расписание сохранено.
- **AC-002 — Durable cleanup новых событий.** После failed insert все attempted/created new IDs
  остаются в `CLEANUP_NEW` до `2xx/404/410`; ошибка cleanup переживает перезапуск и повторяется без
  удаления old IDs. Unattempted prepared ID в cleanup не попадает.
- **AC-003 — Сначала полностью создать новое.** Ни один old delete не вызывается до подтверждения
  всех prepared ID; pre-insert checkpoint сам не подтверждает ID, а insert `409` с заранее
  назначенным ID считается подтверждённым шагом.
- **AC-004 — Возобновляемое удаление old.** Ошибка удаления старого события сохраняет текущий и
  оставшиеся ID; retry продолжает только `DELETE_OLD`, не повторяет insert и не создаёт дубликаты.
- **AC-005 — Восстановление после смерти процесса.** Новый экземпляр repository/worker продолжает
  операцию из любого durable checkpoint (`CREATE_NEW`, `CLEANUP_NEW`, `DELETE_OLD`, terminal
  commit window): caught insert IOException durable cleanup-ится, crash/cancellation после
  server apply остаётся CREATE_NEW и подтверждается same-ID 409, delete timeout подтверждается
  404. Итоговый active корректен, ID не теряются; Unreadable journal не трактуется как отсутствие.
- **AC-006 — Идемпотентный delete.** Ответы delete `2xx`, `404` и `410` одинаково подтверждают шаг
  и удаляют ID из pending; другие HTTP/IO ошибки ID не удаляют.
- **AC-007 — Общий busy/single-flight.** Повторные Save/Clear во время операции не запускают
  второй ViewModel job; обе кнопки одновременно disabled, а UI и worker не входят в state machine
  параллельно благодаря singleton `Mutex`. Enqueue в финальном окне worker не теряется и создаёт
  следующий элемент unique chain.
- **AC-008 — Различимые UI-результаты.** Пользователь различает успешное сохранение, успешную
  очистку, сохранённое old при insert failure, незавершённое удаление/cleanup и необходимость
  Google consent/account correction; ошибки не скрываются в логах.
- **AC-009 — Ad-hoc не изменён.** `CalendarRepository` продолжает отправлять ad-hoc event без
  JSON-полей `id` и `recurrence`; его schedule/cancel и локальная запись работают как прежде.
- **AC-010 — Безопасный Clear.** Clear удаляет remote old events раньше атомарной записи пустого
  active; при ошибке/падении active и remaining IDs сохраняются, а recovery завершает delete без
  insert. Legacy ownerless active сначала adopts текущий email, mismatch/sign-out блокируют API,
  а уже пустой active очищается без auth/API.

## Текущий поток

```text
ScheduleEditor -> CalendarViewModel.saveSchedule
  -> WeeklyScheduleRepository.save
  -> token
  -> read active
  -> DELETE ALL old remote
  -> persist active = empty
  -> INSERT new remote one-by-one
  -> persist only already-created new
```

Опасные окна: первый failed insert уже уничтожает old remote; process death может оставить
частичный active, orphan new events или потерянные old/new IDs. Save и Clear имеют независимые
jobs без общего busy-флага.

## Целевой поток и ownership данных

```text
ScheduleEditor events
  -> CalendarViewModel shared scheduleActionInFlight / isScheduleBusy
  -> @Singleton WeeklyScheduleRepositoryImpl Mutex
      -> active DataStore: weekly_schedule (публичный SSOT)
      -> journal DataStore: pending_operation (внутренний durable writer state)
      -> CalendarApi idempotent steps
      -> WeeklyScheduleRecoveryScheduler (при незавершённом результате)

GymApplication.onCreate
  -> enqueueUniqueWork(weekly_schedule_recovery, APPEND_OR_REPLACE)
  -> WeeklyScheduleRecoveryWorker
  -> same @Singleton repository Mutex -> resumePendingOperation()
```

Save state machine:

```text
PREPARE snapshot + IDs
  -> journal CREATE_NEW
  -> [durable add ID to cleanupNewIds, keep it pending
      -> insert (2xx/409)
      -> durable remove only that confirmed ID from pendingCreateIds]
  -> all confirmed -> journal DELETE_OLD
  -> [delete old (2xx/404/410) -> checkpoint pending]
  -> pending empty -> atomic active edit(target) -> clear journal

insert failure
  -> journal CLEANUP_NEW
  -> [delete attempted/created new -> checkpoint pending]
  -> cleanup empty -> clear journal; active old unchanged

caught insert IOException/HTTP -> durable CLEANUP_NEW -> cleanup attempted IDs
crash/CancellationException after server apply -> stays CREATE_NEW -> same-ID retry -> 409 confirm
```

Clear state machine:

```text
journal CLEAR + DELETE_OLD(old IDs, target empty)
  -> [delete old -> checkpoint pending]
  -> pending empty -> atomic active edit(empty) -> clear journal
```

## Замороженные контракты

### Journal model

В новом `data/schedule/WeeklyScheduleOperation.kt` определить `@Serializable internal` модели:

- `WeeklyScheduleOperationKind { REPLACE, CLEAR }`;
- `WeeklyScheduleOperationPhase { CREATE_NEW, CLEANUP_NEW, DELETE_OLD }`;
- `PreparedCalendarEvent(eventId: String, rule: DayRule, request: CalendarEventDto)`;
- `WeeklyScheduleOperation(kind, phase, accountEmail, oldSchedule, targetSchedule,
  preparedEvents, pendingCreateIds, cleanupNewIds, pendingDeleteIds, failureMessage)`.
- `WeeklyScheduleOperationRead.Absent`, `.Present(operation)`, `.Unreadable(cause)`.

`targetSchedule` для REPLACE уже содержит те же заранее назначенные `calendarEventId` и
`ownerEmail`; для CLEAR это `WeeklySchedule()`. `pendingCreateIds` означает только
неподтверждённые prepared IDs, `cleanupNewIds` — только IDs, для которых durable checkpoint
случился до фактической попытки. Decode malformed JSON, Preferences corruption или DataStore IO
не должны молча стирать journal и запускать новую замену: typed `Unreadable` возвращает
paused/permanent failure, active остаётся прежним, Calendar API не вызывается.

### Repository/recovery contract

`WeeklyScheduleRepository` сохраняет `observe/save/clear` и получает:

```kotlin
suspend fun resumePendingOperation(): WeeklyScheduleRecoveryResult
```

`WeeklyScheduleRecoveryResult` имеет минимум `Completed`, `NothingPending`, `Retry`, `Paused`.
`Retry` используется для IO/429/5xx во время cleanup/delete/recovery и приводит worker к
`Result.retry()`; пойманный insert IO сначала durable переводит CREATE_NEW в CLEANUP_NEW.
`Paused` (consent,
account mismatch, malformed journal или permanent 4xx) сохраняет journal и завершает worker без
busy-loop. Интерактивный `save/clear` по-прежнему возвращает `ScheduleResult`, включая конкретный
`Failure.message`, и при durable незавершённой операции вызывает recovery scheduler.

До любых auth gates recovery проверяет local terminal condition. Для `DELETE_OLD` с пустым
`pendingDeleteIds` он без сети повторяет active target commit и journal clear; current email может
быть null или не совпадать. Account/token проверяются только непосредственно перед API step.

При HTTP `409` repository считает insert успешным только потому, что request несёт тот же
client-generated ID. Любой другой insert 4xx — failure и переход в cleanup. Для delete only
`2xx/404/410` удаляют pending ID.

Active contract меняется backward-compatible полем
`WeeklySchedule.ownerEmail: String? = null`. Отдельный weekly auth seam:

```kotlin
interface AccountBoundGoogleAuth {
    suspend fun getAccessTokenForAccount(expectedEmail: String): TokenResult
}
```

реализуется `GoogleAuthManager` через `AuthorizationRequest.Builder.setAccount(
android.accounts.Account(expectedEmail, "com.google"))`. Weekly repository сначала fail-closed
читает active/current email из одного Preferences snapshot; internal
`adoptLegacyOwnerOrReadActive()` при legacy ownerless non-empty active одним settings edit
повторно читает `google_email`/active и записывает owner, затем запрашивает token именно для этого
owner. После этого settings race не может
перенаправить API в другой аккаунт. `GoogleModule.bindAccountBoundGoogleAuth` связывает тот же
singleton manager без default fallback к произвольному token. Текущий `GoogleAuth.getAccessToken()`
и все ad-hoc/Sheets consumers/fakes сохраняются без изменения.

Для проверяемости `GoogleAuthManager` выделяет `internal fun buildAuthorizationRequest(
expectedEmail: String?): AuthorizationRequest`: оба token paths используют один builder,
account-bound path передаёт email, legacy path — null. Unit/Robolectric test проверяет публичный
`AuthorizationRequest.getAccount()` (`name == expectedEmail`, `type == "com.google"`) и что
no-arg request account не задаёт. Интерактивный `authorize(activity)` перед builder читает
persisted email и передаёт его как expected; тест consent request отдельно доказывает account A.
Если выбран явный `authorizeForAccount`, Settings/weekly consent caller обязан передать owner A,
а generic authorize не должен использоваться для pending weekly operation.

### Calendar API contract

В `CalendarEventDto` добавить первым либо именованным nullable-полем:

```kotlin
@EncodeDefault(EncodeDefault.Mode.NEVER)
val id: String? = null
```

`CalendarRepositoryImpl` не передаёт `id`; recurring builder всегда передаёт journal event ID.
Публичные Retrofit endpoint/response не меняются.

### WorkManager contract

`WeeklyScheduleRecoveryWorker` — `@HiltWorker CoroutineWorker`; `doWork` не принимает journal в
InputData и всегда читает его через repository. Scheduler создаёт one-time request без network
constraint, с exponential 30 s и
`enqueueUniqueWork("weekly_schedule_recovery", APPEND_OR_REPLACE, request)`.
После `AuthorizeOutcome.Granted` настройки вызывают отдельный recovery `wake()` с `REPLACE`,
чтобы paused journal продолжился без перезапуска приложения и без ожидания старого backoff.
Новый enqueue всегда остаётся за уже running worker, поэтому последний read не может поглотить
wakeup. Worker не имеет
жёсткого лимита попыток, потому что journal — источник истины; permanent/interactive блокировки
мапятся в success/paused, а не в бесконечный retry.

### UI contract

`CalendarViewModel` публикует immutable `StateFlow<Boolean> isScheduleBusy`. Один helper запускает
Save или Clear только при atomic смене false -> true и сбрасывает busy в `finally`. Обе команды
используют один gate. `ScheduleEditorScreen` собирает flow lifecycle-aware и передаёт
`enabled = !isScheduleBusy` в Save `PillButton` и Clear `TextButton`; тексты и layout не меняются.
Навигация и draft state restoration остаются текущими.

## Архитектурные решения по Android quality gates

- **SSOT/UDF:** Compose не читает journal и не вызывает worker/API. Active DataStore остаётся
  единственным отображаемым состоянием; события идут UI -> ViewModel -> repository.
- **Coroutines/cancellation:** сетевые suspend-вызовы main-safe через Retrofit/DataStore;
  дополнительный dispatcher и `flowOn` не нужны. `CancellationException` ловится отдельно и
  пробрасывается, а все catches ограничены `HttpException`/`IOException`.
- **DI/scopes:** оба DataStore singleton; journal отличает qualifier
  `@WeeklyScheduleOperations`. Repository singleton уже задан `GoogleModule` и владеет Mutex.
  Worker получает тот же interface через Hilt.
- **WorkManager:** persistent deferrable recovery, уникальная unconstrained
  `APPEND_OR_REPLACE` chain и backoff; retries идемпотентны за счёт preassigned IDs и durable
  pending-наборов, локальные terminal markers завершаются офлайн, а enqueue в финальном окне
  running worker не теряется.
- **Room:** не затронут, миграции и schema export не нужны.
- **Permissions/security:** Calendar OAuth scope уже существует; новых разрешений/manifest
  components нет. Journal содержит email, request summary/time/routine ID и event IDs в private
  DataStore; access token не сохраняется. Token weekly-operation account-bound через
  `AuthorizationRequest.setAccount`, active owner переносится backward-compatible.
- **Backup/transfer:** active settings продолжает переноситься; dedicated journal file исключён
  из legacy full backup, API 31+ cloud backup и device transfer. Это исключает replay stale
  in-flight операции на другом устройстве; XML проверяется Robolectric/static test.
- **Navigation/restoration/adaptive/accessibility:** маршруты и layout не меняются. Disabled-state
  стандартных M3 Button/TextButton доступен semantics; touch target сохраняется. Проверить при
  ревью, что `fontScale=2.0` и compact/expanded не регрессировали, без screenshot.
- **Background/app start:** `GymApplication` только ставит unique work, не запускает сеть напрямую
  и не удерживает процесс.
- **Dependencies/R8:** dependency не добавляется, но новые serializable journal types и Hilt worker
  затрагивают release/R8 surface, поэтому обязателен `assembleRelease`.

## Задачи

### T-001 — Зафиксировать wire/journal-контракты

- **Owner:** `android_feature_implementer` (единственный writer).
- **Файлы:**
  - `app/src/main/java/com/valerochka1337/valerochkagym/data/google/CalendarApi.kt`;
  - `app/src/main/java/com/valerochka1337/valerochkagym/data/google/GoogleAuth.kt`;
  - `app/src/main/java/com/valerochka1337/valerochkagym/data/google/GoogleAuthManager.kt`;
  - `app/src/main/java/com/valerochka1337/valerochkagym/di/GoogleModule.kt`;
  - `app/src/main/java/com/valerochka1337/valerochkagym/data/schedule/WeeklySchedule.kt`;
  - новый `app/src/main/java/com/valerochka1337/valerochkagym/data/schedule/WeeklyScheduleOperation.kt`;
  - `app/src/test/java/com/valerochka1337/valerochkagym/data/CalendarEventDtoTest.kt`;
  - новый `app/src/test/java/com/valerochka1337/valerochkagym/data/GoogleAuthManagerTest.kt`
    либо расширенный существующий auth test;
  - новый/расширенный serialization test для journal рядом с
    `WeeklyScheduleRepositoryTest.kt` либо отдельный
    `data/WeeklyScheduleOperationTest.kt`.
- **Dependencies:** нет.
- **Actions:** добавить nullable omitted `CalendarEventDto.id` и nullable default
  `WeeklySchedule.ownerEmail`; определить serializable journal models, typed read и валидатор
  инвариантов/32-hex ID; добавить отдельный `AccountBoundGoogleAuth` и binding с
  `AuthorizationRequest.setAccount`; привязать interactive authorize/consent к persisted expected
  email (либо явному owner argument);
  не менять existing no-arg auth/ad-hoc builder.
- **Automated verification:**
  `./gradlew :app:testDebugUnitTest --tests "*CalendarEventDtoTest" --tests "*WeeklyScheduleOperationTest" --tests "*GoogleAuthManagerTest"`
- **Done:** recurring DTO сериализует заданный ID; ad-hoc JSON не содержит `id` и `recurrence`;
  old WeeklySchedule JSON декодируется с null owner; journal round-trip сохраняет каждый pending
  ID/phase/snapshot и отвергает нарушенные инварианты; token и interactive consent request
  привязаны к expected account A.
- **AC:** AC-003, AC-005, AC-009, AC-010.

### T-002 — Реализовать durable state machine в repository

- **Owner:** `android_feature_implementer`.
- **Файлы:**
  - `app/src/main/java/com/valerochka1337/valerochkagym/data/schedule/WeeklyScheduleRepository.kt`;
  - `app/src/main/java/com/valerochka1337/valerochkagym/data/schedule/WeeklyScheduleOperation.kt`;
  - `app/src/test/java/com/valerochka1337/valerochkagym/data/WeeklyScheduleRepositoryTest.kt`.
- **Dependencies:** T-001.
- **Actions:** подготовить immutable requests и IDs; записывать journal до сети; реализовать
  `CREATE_NEW -> CLEANUP_NEW | DELETE_OLD`, pre-insert attempted checkpoint без удаления pending,
  post-2xx/409 confirmation checkpoint,
  `2xx/404/410` delete success, terminal active edit и journal clear; добавить `Mutex`, account
  adoption/owner mismatch/account-bound token, typed fail-closed reads, clear flow и явный rethrow
  CancellationException. Перед новым Save/Clear завершать существующий journal либо возвращать
  различимую blocked/deferred ошибку. Stateful fake backend хранит remote IDs и умеет применить
  insert/delete, затем бросить timeout/cancellation.
- **Automated verification:**
  `./gradlew :app:testDebugUnitTest --tests "*WeeklyScheduleRepositoryTest"`
- **Done:** fault-injection/reinstantiation tests доказывают AC-001..AC-006 и AC-010; есть crash
  между attempted checkpoint/API; caught insert applied-then-IOException durable переходит в
  CLEANUP_NEW и cleanup-ит ID; crash/cancellation after insert apply оставляет CREATE_NEW и retry
  подтверждается 409; delete-applied-timeout -> 404; cancellation checkpoint и terminal replay
  до/после active commit при current email null/mismatch; legacy adoption,
  owner mismatch, sign-out, settings/token race и Absent/Present/Unreadable (JSON, IO, corruption)
  fail-closed. Active ни в одном error checkpoint не становится частичным; retry DELETE_OLD не
  вызывает insert. Если API бросает `CancellationException` после durable checkpoint, repository
  enqueue-ит recovery и затем пробрасывает cancellation без преобразования в Failure.
- **AC:** AC-001, AC-002, AC-003, AC-004, AC-005, AC-006, AC-008, AC-010.

### T-003 — Подключить отдельный DataStore, backup policy и persistent recovery

- **Owner:** `android_feature_implementer`; shared choke points принадлежат только этому writer.
- **Файлы:**
  - `app/src/main/java/com/valerochka1337/valerochkagym/data/schedule/WeeklyScheduleRepository.kt`
    (подключение enqueue после durable незавершённого результата);
  - новый `app/src/main/java/com/valerochka1337/valerochkagym/di/WeeklyScheduleOperations.kt`;
  - `app/src/main/java/com/valerochka1337/valerochkagym/di/DataModule.kt`;
  - новый `app/src/main/java/com/valerochka1337/valerochkagym/worker/WeeklyScheduleRecoveryWorker.kt`;
  - новый `app/src/main/java/com/valerochka1337/valerochkagym/worker/WeeklyScheduleRecoveryScheduler.kt`
    (или scheduler в companion worker, но имя unique-work остаётся одним публичным контрактом);
  - `app/src/main/java/com/valerochka1337/valerochkagym/GymApplication.kt`;
  - `app/src/main/res/xml/backup_rules.xml`;
  - `app/src/main/res/xml/data_extraction_rules.xml`;
  - новый `app/src/test/java/com/valerochka1337/valerochkagym/worker/WeeklyScheduleRecoveryWorkerTest.kt`;
  - новый `app/src/test/java/com/valerochka1337/valerochkagym/worker/WeeklyScheduleRecoverySchedulerTest.kt`;
  - новый `app/src/test/java/com/valerochka1337/valerochkagym/GymApplicationTest.kt`;
  - новый `app/src/test/java/com/valerochka1337/valerochkagym/data/WeeklyScheduleBackupRulesTest.kt`.
- **Dependencies:** T-002.
- **Actions:** создать qualified singleton dedicated journal DataStore; внедрить его в repository;
  добавить Hilt worker и unique unconstrained scheduler: обычный enqueue использует
  APPEND_OR_REPLACE, а user-driven wake после consent — REPLACE; enqueue в
  `GymApplication.onCreate`, после незавершённого интерактивного шага и успешного Google consent;
  исключить ровно
  `datastore/weekly_schedule_operations.preferences_pb` из legacy backup и обеих API 31+ секций.
  Не менять manifest и dependency catalog.
- **Automated verification:**
  `./gradlew :app:testDebugUnitTest --tests "*WeeklyScheduleRecoveryWorkerTest" --tests "*WeeklyScheduleRecoverySchedulerTest" --tests "*GymApplicationTest" --tests "*WeeklyScheduleBackupRulesTest"`
- **Done:** worker map `Completed/NothingPending -> success`, transient `Retry -> retry`,
  `Paused -> success` без очистки journal; scheduler создаёт ровно одну работу
  `weekly_schedule_recovery` chain с NOT_REQUIRED/APPEND_OR_REPLACE/backoff. Тест подтверждает,
  что локальный terminal marker не блокируется офлайн, а успешный consent заменяет ожидающий
  backoff и немедленно будит paused recovery.
  Adversarial test ставит
  новый journal+enqueue после последнего read первого worker, но до его finish, и доказывает запуск
  successor. Robolectric startup test подтверждает `GymApplication.onCreate` enqueue и сохранение
  `HiltWorkerFactory` в Configuration. XML test подтверждает exclusion journal в cloud/transfer и
  отсутствие exclusion для `settings.preferences_pb`.
- **AC:** AC-002, AC-004, AC-005, AC-006, AC-007, AC-010.

### T-004 — Сделать Save/Clear общим UI single-flight и различить результаты

- **Owner:** `android_feature_implementer`.
- **Файлы:**
  - `app/src/main/java/com/valerochka1337/valerochkagym/ui/calendar/CalendarViewModel.kt`;
  - `app/src/main/java/com/valerochka1337/valerochkagym/ui/calendar/ScheduleEditorScreen.kt`;
  - `app/src/main/java/com/valerochka1337/valerochkagym/ui/settings/SettingsViewModel.kt`;
  - `app/src/test/java/com/valerochka1337/valerochkagym/ui/CalendarViewModelTest.kt`;
  - новый `app/src/test/java/com/valerochka1337/valerochkagym/ui/ScheduleEditorScreenTest.kt`;
  - новый `app/src/test/java/com/valerochka1337/valerochkagym/ui/SettingsRecoverySchedulingTest.kt`.
- **Dependencies:** T-002, T-003.
- **Actions:** единый atomic/job gate Save/Clear, immutable busy flow, `finally` reset; отключить обе
  кнопки при busy; сохранить hardcoded UI strings и вывести точные repository messages для
  deferred cleanup/delete/account mismatch; после успешного Google consent enqueue-ить paused
  recovery. Не менять ad-hoc actions, drafts или navigation.
- **Automated verification:**
  `./gradlew :app:testDebugUnitTest --tests "*CalendarViewModelTest" --tests "*ScheduleEditorScreenTest" --tests "*SettingsRecoverySchedulingTest"`
- **Done:** suspended fake доказывает, что rapid Save+Clear/Save+Save вызывает repository один раз,
  обе команды снова доступны после success/failure/cancellation; тесты различают save/clear success
  и минимум один deferred failure message. Robolectric Compose semantics test кликает Save на
  suspended repository, проверяет `assertIsNotEnabled()` у Save и Clear и `assertIsEnabled()` у
  обеих после release.
- **AC:** AC-001, AC-007, AC-008, AC-009, AC-010.

### T-005 — Зафиксировать архитектуру и один version bump

- **Owner:** `android_feature_implementer`.
- **Файлы:**
  - `ARCHITECTURE.md`;
  - `app/build.gradle.kts`.
- **Dependencies:** T-001..T-004 стабильны.
- **Actions:** описать active SSOT + journal state machine, idempotent Calendar IDs и recovery
  worker; один раз изменить базовые значения `versionCode 10 -> 11` и
  `versionName "1.3.2" -> "1.3.3"`, не меняя `testVersion*` override.
- **Automated verification:**
  `rg -n "versionCode = testVersionCode \?: 11|versionName = testVersionName \?: \"1.3.3\"" app/build.gradle.kts`
- **Done:** документация совпадает с реализацией; diff содержит ровно один patch-инкремент и не
  меняет dependency/build topology.
- **AC:** AC-005 и release-инвариант проекта.

### T-006 — Полная проверка, review и Git/PR handoff

- **Owner:** `android_feature_tester` выполняет независимые gates; `android_feature_reviewer`
  делает read-only diff review; исправления — исходный `android_feature_implementer`; commit/push/PR
  — только main agent по явному запросу пользователя.
- **Файлы:** все файлы T-001..T-005 и этот plan/tracker; код в этом task не добавляется, кроме
  исправления подтверждённых findings implementer-ом.
- **Dependencies:** T-005.
- **Actions:** подтвердить AC traceability, отсутствие открытых P0/P1 и исправить P2 либо записать
  residual risk; выполнить final gates строго последовательно; затем проверить version freshness
  относительно актуальной remote `main`, clean status и intended diff. Если `main` уже содержит
  версию >= 11/1.3.3, до push rebase/update и выставить оба значения строго выше `main`, но не
  делать второй bump за ту же фичу после уже разрешённого конфликта.
- **Automated verification:**
  1. `./gradlew :app:testDebugUnitTest`
  2. `./gradlew :app:assembleDebug`
  3. `./gradlew :app:assembleRelease`
  4. `git diff --check`
  5. read-only Git checks: `git status --short`, `git diff --name-status main...HEAD`, сравнение
     version с `origin/main` после `git fetch origin main`.
- **Done:** три Gradle-команды успешны (либо для release записан точный signing blocker), diff
  чистый от случайных файлов, reviewer verdict без P0/P1, tracker заполнен. Main agent создаёт один
  русский смысловой commit (рекомендуемо `fix: сделать замену расписания безопасной`), push ветки
  `fix/weekly-schedule-safe-replacement` и PR в `main` с ссылкой/`Closes #8`; после push проверяет
  PR checks. Не merge и не удалять ветку без отдельного запроса.
- **AC:** AC-001..AC-010.

## File ownership

| Область | Файлы | Writer |
|---|---|---|
| Calendar/auth wire contract | `data/google/CalendarApi.kt`, `GoogleAuth.kt`, `GoogleAuthManager.kt`, `di/GoogleModule.kt` | один `android_feature_implementer` |
| Durable schedule aggregate | `data/schedule/WeeklySchedule.kt`, `WeeklyScheduleRepository.kt`, новый `WeeklyScheduleOperation.kt` | тот же implementer |
| DataStore/DI choke point | `di/DataModule.kt`, новый qualifier | тот же implementer |
| Persistent recovery | новые `worker/WeeklyScheduleRecovery*.kt`, `GymApplication.kt` | тот же implementer |
| Machine-local backup policy | `res/xml/backup_rules.xml`, `data_extraction_rules.xml` | тот же implementer |
| UI state/actions | `ui/calendar/CalendarViewModel.kt`, `ScheduleEditorScreen.kt` | тот же implementer |
| Regression tests | Calendar DTO, repository, worker/scheduler, ViewModel tests | тот же implementer |
| Version/docs | `app/build.gradle.kts`, `ARCHITECTURE.md` | тот же implementer |
| Plan tracking | два `vibe/weekly-schedule-safe-replacement-*` файла | planner/main agent; implementer обновляет только tracker rows/results |
| Review/Git | read-only reviewer/tester; commit/push/PR | только main agent |

Room, navigation, Hilt modules, version catalog и manifest не передаются другому writer. Если
потребуется неожиданное изменение shared choke point, сначала записать deviation и вернуть его
main agent; параллельных implementer-ов не добавлять.

## Execution waves

| Wave | Tasks | Параллельность |
|---|---|---|
| 1 | T-001 | один writer |
| 2 | T-002 | после frozen wire/journal contract |
| 3 | T-003 | после стабильного repository recovery contract |
| 4 | T-004 | после frozen result/busy contract |
| 5 | T-005 | после production/tests |
| 6 | T-006 tester + reviewer | могут идти параллельно только если reviewer read-only и Gradle запускает один tester; fix loop последовательный |

Не запускать конкурентные Gradle-процессы в одном checkout. Максимум два fix/review pass.

## Проверки и quality gates

### Targeted

```bash
./gradlew :app:testDebugUnitTest --tests "*CalendarEventDtoTest" --tests "*WeeklyScheduleOperationTest" --tests "*GoogleAuthManagerTest"
./gradlew :app:testDebugUnitTest --tests "*WeeklyScheduleRepositoryTest"
./gradlew :app:testDebugUnitTest --tests "*WeeklyScheduleRecoveryWorkerTest" --tests "*WeeklyScheduleRecoverySchedulerTest" --tests "*GymApplicationTest" --tests "*WeeklyScheduleBackupRulesTest"
./gradlew :app:testDebugUnitTest --tests "*CalendarViewModelTest" --tests "*ScheduleEditorScreenTest"
```

### Обязательные сценарии тестов

- positive replace и clear;
- empty active clear/save-empty: success без email/token/journal/API;
- failure на первом/среднем insert, затем успешный и failed cleanup;
- crash/cancellation после durable attempted checkpoint, но до insert: ID остаётся pending и
  cleanup-eligible, unattempted IDs не cleanup-ятся;
- stateful remote: insert применён, затем API бросил `IOException` -> durable CLEANUP_NEW и delete
  attempted ID; отдельный crash/cancellation после server apply до success checkpoint ->
  CREATE_NEW retry 409; delete применён, затем `IOException` -> DELETE_OLD retry 404;
- call-order всех inserts до первого old delete;
- insert 409 и повтор после recreation без дубля;
- delete 404/410 как success; 401/403/429/5xx/IOException сохраняют pending по принятой
  permanent/transient классификации;
- recreation на каждой phase и terminal window;
- terminal replay до active commit и после active commit/до journal clear при current email null и
  при mismatch;
- ownerless legacy non-empty adoption до API; owner mismatch, sign-out/empty email и
  settings-change while account-bound token is pending не вызывают API другого аккаунта;
- interactive consent/authorize request закреплён за persisted/explicit expected owner A;
- journal read Absent/Present/Unreadable; invalid JSON, DataStore IOException/corruption fail-closed;
- CancellationException пробрасывается, последующий recovery видит durable checkpoint;
- enqueue after worker last read/before finish запускает appended successor;
- `GymApplication` startup enqueue использует HiltWorkerFactory configuration;
- rapid Save/Save, Save/Clear и Clear/Clear;
- Compose semantics: обе action disabled во время busy и re-enabled после;
- backup XML исключает только weekly operation journal в legacy/cloud/device-transfer, но не active;
- ad-hoc serialization/API tests остаются зелёными.

### Final gates (строго последовательно)

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

`assembleRelease` условно обязателен именно здесь из-за новых kotlinx-serialization моделей,
Hilt Worker/WorkManager и R8. Если release signing inputs недоступны, не подменять gate debug-
сборкой: записать точное сообщение `validateSigningRelease` и проверить release в CI/среде с
ключом до merge.

Не запускать `AnalysisRenderTest` отдельно и не открывать его snapshots: графики не изменяются,
визуальная проверка пользователем не запрошена.

## Риски и защита

| Риск | Мера |
|---|---|
| Crash/cancellation между server apply и success checkpoint | ID остаётся CREATE_NEW pending; same-ID retry -> 409 confirm; CancellationException пробрасывается и не переводит phase |
| Insert вернул пойманный IOException, в том числе after apply | durable CLEANUP_NEW; cleanup attempted IDs получает 2xx/404 и old остаётся active |
| Crash между active commit и journal clear | journal с empty pending остаётся terminal marker; commit повторяется идемпотентно |
| Новые события созданы, old delete временно упал | active остаётся old; journal DELETE_OLD хранит target и remaining old IDs; retry не insert-ит |
| Failed insert с неопределённым сетевым исходом | attempted ID заранее в cleanupNewIds; cleanup delete безопасен даже при 404 |
| Сеть недоступна и для insert, и для компенсационного delete | journal остаётся в CLEANUP_NEW, worker получает Retry и повторяет только cleanup после backoff |
| Пользователь сменил Google account | snapshot email mismatch ставит операцию на паузу до исходного аккаунта, исключая удаление чужих событий |
| Locale устройства меняет Unicode case mapping | email и account binding нормализуются через `lowercase(Locale.ROOT)` |
| Legacy non-empty active не имеет owner | Adoption текущего persisted email — backward-compat heuristic, не доказательство исторического владельца; если remote series была A, а первый upgrade operation запущен под B, A может остаться orphan |
| Persisted email меняется во время token request | weekly auth request закрепляет `Account(expectedEmail, "com.google")`; returned token не следует новой настройке |
| Два UI action или UI + worker | общий ViewModel gate + singleton repository Mutex + unique APPEND_OR_REPLACE chain |
| Enqueue в конце running worker | successor append-ится и повторно читает journal после завершения predecessor |
| Повреждённый JSON journal | active не трогать, не начинать новую операцию, вернуть paused/permanent UI error; не молча очищать |
| Stale journal восстановлен на другом device | dedicated journal file исключён из cloud backup и device transfer; active переносится с owner |
| Удалённая ручная правка события | delete 404/410 закрывает pending; prepared snapshot не зависит от чтения remote event |
| Release shrinker удалит serializer/worker wiring | targeted unit tests + обязательный assembleRelease |

## Rollback и сохранность данных

- Нет Room migration и необратимых локальных преобразований.
- До terminal commit активный `weekly_schedule` всегда старый целый snapshot. Удалять journal
  вручную при rollback нельзя, если он содержит remote IDs: сначала нужно завершить соответствующий
  cleanup/delete тем же кодом.
- Откат APK после начала новой операции опасен: старая версия не понимает второй journal и может
  запустить destructive Save/Clear. До release это проверяется review/CI; после выпуска rollback
  должен либо включать recovery reader, либо сопровождаться завершением journal новой версией.
- Journal не содержит token/secret и является machine-local: он намеренно исключён из backup и
  transfer, тогда как active settings переносится. Если устройство потеряно посреди CREATE_NEW,
  исключение journal предотвращает опасный replay на новом device, но уже применённый remote new
  event может остаться orphan; это ограниченный residual risk, который невозможно устранить без
  server-side transaction/listing managed events. Active old при этом не потерян.
- `ownerEmail = null` у legacy JSON не позволяет восстановить исходный Google account. Принятая
  эвристика закрепляет первый persisted authenticated email. Она предотвращает дальнейшие races,
  но не гарантирует legacy safety: если историческая series принадлежит A, а после upgrade первым
  активен B, события A могут осиротеть. Автоматически probing/deleting across accounts запрещено;
  это accepted residual до server-side ownership metadata.

## Нерешённые вопросы

Product-changing blockers отсутствуют: Gate R и plan-review допущения заморожены
пользователем/родительским агентом. Реализационные детали, которые reviewer обязан проверить:

- Google Calendar `409` принимается как успех только для preassigned ID текущего journal, не для
  произвольного server-returned ID;
- malformed journal нельзя автоматически потерять; выбранная безопасная стратегия — pause + UI
  error, а не destructive reset;
- permanent OAuth/account state не должен создавать бесконечный WorkManager retry; journal остаётся
  для следующего app start/ручного действия;
- fallback `REPLACE` запрещён при штатной WorkManager 2.11.2; допустим лишь если компилятор реально
  не видит `APPEND_OR_REPLACE`, с deviation и adversarial cancellation/replacement test.

## Git/PR guardrails

- Ветка уже создана: `fix/weekly-schedule-safe-replacement`; implementer не создаёт/не переключает
  ветки и не делает worktree.
- До push main agent выполняет `git fetch origin main`, убеждается, что ветка относится к issue #8,
  и сравнивает version с актуальной `origin/main`.
- Commit/push/PR явно разрешены пользователем, но выполняются только main agent после Gate T/V и
  final gates. PR target — `main`, описание перечисляет AC evidence, команды и `Closes #8`.
- Merge, force-push и удаление ветки не входят в запрос.
