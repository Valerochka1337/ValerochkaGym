# AI-01 backend — Gate R, рекомендуемый контракт

10.09.2026. Read-only исследование `/private/tmp/yarumo-backend-delivery`, после CAL-01
`c73c411`. Код/backend-файлы, Git, Gradle, production и реальные `.env` не изменялись.
Это конкретная рекомендация для Gate P, не заявление о готовности production AI.
Согласованный объём: перенос существующих генерации упражнения и распознавания фото InBody.
Календарный планировщик/предложения, PDF и медицинская сводка остаются последующим этапам.

## Подтверждённые seams

- `controller/data/DataController.kt`, `controller/model/SyncModels.kt`: типизированные DTO,
  аутентифицированный Identity, заголовки capabilities; legacy/v2/v3 сохраняются.
- `config/Security.kt` требует auth на новых `/v1/ai/*`
  без нового permitAll. `security/BearerFilter.kt` проверяет текущую сессию, ограничивает запрос
  10 MiB по Content-Length и потоковому чтению; существующий лимит user 300/min остаётся.
- `repository/data/SyncRepositories.kt`: `ensureHead`, read/write lock по owner;
  `RecordRepository.findByUserIdOrderByKindAscIdAsc`. `SyncService` блокирует каталог перед
  owner head — новый сборщик должен соблюдать тот же порядок, чтобы избежать deadlock.
- `repository/catalog/*` и `CatalogService`: общий каталог с revision/active/archived и equipment.
  Собираются только разрешённые владельцу личные живые упражнения и доступный общий каталог.
  Нельзя использовать `RecordRepository.findAll` или `findByIdIn` без ограничения владельца.
- `RecordValidator` содержит enums мышц/типа, reference/equipment checks; его полную функцию
  exercise payload нельзя применять прямо к AI draft, которому ещё не назначены sync fields.
- `application.yml`: Jackson reject unknown properties; DB pool 8, Tomcat 40 threads.
  Production `infra/compose.production.yaml`: 768MiB backend, read-only root, `/tmp` tmpfs 64MiB,
  outbound network, `env_file: .env`. Отдельных AI config/provider/classes сейчас нет.
- Android `InBodyPhotoEncoder`: уже JPEG ≤6MiB, max side 3072; текущий draft — nullable factual
  values/date/time и segment map. Exercise result — Existing local Long или New(name,type,loads).
  Wire Existing должен использовать UUID, локальный Long разрешает Android после sync.

## Рекомендуемый freeze DTO v1

Это предложение целиком для согласования Android/backend. Ни одного provider endpoint/model/key,
готового prompt, arbitrary URL, ownerId или клиентского snapshot в запросе.
Все дополнительные поля отклоняются. Все UUID канонические lowercase.

`GET /v1/ai/status` (auth):

```json
{"schemaVersion":1,"availability":"AVAILABLE","actions":["EXERCISE_DRAFT","INBODY_PHOTO_DRAFT"]}
```

В выключенном/неполном окружении availability=`UNCONFIGURED`, actions=[]; возвращается 200 без
сетевого probe. AVAILABLE означает наличие валидной конфигурации, не доказанную доступность
провайдера. Model catalog/UI выбора ключа не нужен; текущие Android прямые models calls удаляются.

`POST /v1/ai/exercise-drafts`:

```json
{"requestId":"UUID","expectedRevision":42,"expectedCatalogRevision":7,"description":"Жим гантелей сидя"}
```

Description trim, 1…2000 символов; revisions nonnegative Long. Ответ 200:

```json
{"requestId":"UUID","context":{"revision":42,"catalogRevision":7},"result":{"kind":"EXISTING","exerciseId":"UUID"}}
```

Или result=`{"kind":"NEW","name":"...","type":"STRENGTH","muscles":[{"muscle":"FRONT_DELTS","contribution":100}]}`.
NEW не назначает persisted id, updatedAt, isCustom, equipment или автоматически запись в БД.
Android применяет существующий editor/create flow; недостающие поля пользователь подтверждает.
EXISTING разрешим только в собранном owner/public live catalog; archived/чужой ID не принимается.
NEW: name 1…200, type STRENGTH|TIMED|CARDIO, уникальные мышцы из RecordValidator.muscles,
contribution 0|50|100, хотя бы одна ненулевая нагрузка. Нельзя silently заменять невалидный ответ.

`POST /v1/ai/inbody-drafts`:

```json
{"requestId":"UUID","expectedRevision":42,"expectedCatalogRevision":7,"image":{"mediaType":"image/jpeg","base64":"..."}}
```

Только явно выбранное пользователем фото; подтверждение передачи backend и его AI-провайдеру
показывается Android до upload. Base64 без data-URL prefix, ≤8MiB символов; decoded ≤6MiB,
JPEG magic + decoder validation, width/height 1…3072. Полный JSON ≤10MiB существующего фильтра.
Чужие URL, local paths, multipart/PDF, дополнительные вложения — 400. Неверный формат —
400 `invalid_image`; превышение байтов — 413 `payload_too_large`. Не декодировать пиксели до
проверки metadata dimensions, не доверять MIME. Чтение ImageIO без disk cache.

Ответ 200: та же requestId/context envelope, `result:{kind:"INBODY",draft:{...}}`.
Draft fields **ровно** текущий Android InBodyReportDraft: measuredDate YYYY-MM-DD|null,
measuredTime HH:mm|null; nullable weightKg, skeletalMuscleMassKg, bodyFatPercentage,
bodyFatMassKg, visceralFatLevel(Int), waistHipRatio, inBodyScore(Int), totalBodyWaterLiters,
proteinKg, mineralsKg, bodyMassIndex, fatFreeMassKg, basalMetabolicRateKcal(Int),
recommendedCalorieIntakeKcal(Int), segments. Для segments фиксируем JSON object с ключами
LEFT_ARM, RIGHT_ARM, TRUNK, LEFT_LEG, RIGHT_LEG; каждый value содержит ровно nullable
leanMassKg, leanPercentage, fatMassKg, fatPercentage. Все пять ключей и все четыре поля
присутствуют, нераспознанные значения null. Segment percentages — отношение к референсу
устройства, поэтому верхняя граница 100 к ним не применяется. Числа конечные и неотрицательные; bodyFatPercentage 0…100,
прочие границы — текущая предметная модель, без выдуманных медицинских «безопасных» порогов.
Неизвестные поля/единицы, невалидная календарная дата/время и полностью пустой результат —
502 `ai_invalid_response`. Нераспознанное значение остаётся null, не 0; нельзя выводить имя,
номер пациента, диагноз или рекомендацию устройства. Никаких measurement writes до согласия.

`requestId` — correlation текущей попытки, **не** sync operationId и не обещание exactly-once
вызова оплачиваемого провайдера. Сервер не сохраняет raw prompt/photo/result в ledger. Повтор
действия может повторно вызвать provider; автоматический retry POST отключён. Для idempotency
и долгоживущих proposals нужна отдельная архитектура PLAN-01, не расширять sync_operations.

## Snapshot freshness и разрешённый контекст

1. Android сначала получает типизированный Ready(owner, revision, catalogRevision) после sync ACK;
   Unit/ранний выход BackendSync.run не равен готовности. Guest, active-workout deferred apply,
   unresolved conflict, pending claim/outbox и смена owner не запускают AI. Зависимость от этапа12.
2. Backend из Identity выбирает owner; в короткой транзакции catalog read lock → ensure/read owner
   head → сравнение обеих revision → сбор immutable context. Mismatch: 409 `ai_context_stale`
   до provider call. Никаких locks/DB connection во время внешнего HTTP.
3. Exercise action включает каталог exercise (личные live + shared nonarchived), валидные muscle
   enums и описание. Не отправляет тренировки/замеры/заметки «на будущее».
   InBody action включает только выбранное фото и серверную схему извлечения; история здоровья
   не нужна этому распознаванию. Проверка revisions остаётся общим owner freshness handshake.
4. Ограничить сериализованный context 1MiB. Если полный каталог больше, 409
   `ai_context_too_large` с ручным fallback, не молча обрезанный «полный» каталог и не SQL full scan
   чужих владельцев. Будущий retrieval требует отдельно описанного поведения.
5. После provider response и программной проверки повторно под короткими locks сравнить обе
   revision и наличие owner/session authorization. Изменение/удаление аккаунта/отзыв сессии
   означает discarded result, 409/401, без записи. Клиент дополнительно проверяет owner/requestId
   и текущий editor generation перед показом — сервер не знает о переключении локального экрана.

## Provider/config/lifetime

Новый `AiProvider` seam принимает server-built typed action/context, возвращает bounded raw JSON;
`AiActionService` владеет prompt construction и предметной валидацией. Никакие строки пользователя,
каталога или OCR-текст не выполняют инструкции сервера и не открывают дополнительные данные/tools.
Конкретный провайдер, его protocol adapter и model остаются deployment decision: нельзя считать
любой "совместимый" endpoint одинаково поддерживающим images/structured output.

Рекомендованные env names: AI_ENABLED=false, AI_PROVIDER, AI_BASE_URL, AI_API_KEY,
AI_TEXT_MODEL, AI_VISION_MODEL. Только server environment; конфигурация не сериализуется,
не логируется и не возвращается Android. HTTPS endpoint, no userinfo/query/fragment,
redirects NEVER; endpoint фиксируется operator config, не клиентом. Unconfigured provider bean
не делает запросов. Action без конфигурации → 503 `ai_unavailable`, ручные функции работают;
общая readiness/db/health не становится красной из-за необязательной AI возможности.

Java21 HttpClient singleton, connect 5s; end-to-end deadline 45s, не только время до заголовков;
response stream/body ≤256KiB, finite max output tokens per adapter, bounded executor/concurrency
(рекомендация 2 in-flight в текущих 768MiB, saturation 503 `ai_busy`, техническая защита ресурсов,
не продуктовая квота #63). HTTP 429/5xx → 503 `ai_unavailable`, timeout → 504 `ai_timeout`,
invalid provider schema → 502 `ai_invalid_response`; никаких upstream body/URL/key в errors.

Не удерживать исходные фото/ответы после попытки. Предпочтён JSON-in-memory seam без temporary
files вообще; bounded buffers обнулять/освобождать best effort в finally, references не кешировать.
Если адаптер требует temp file — только случайный private /tmp, удаление в finally при success,
failure, timeout и cancellation, без provider Files upload/persistence в этой версии.

Отмена HTTP в Android прекращает ожидание и применение результата. MVC disconnect не гарантирует
мгновенную остановку provider; async timeout/error callbacks должны cancel(true) underlying future,
а deadline гарантирует ограниченный lifecycle. Java21 описывает отмену как попытку, запрос уже
может быть отправлен — не обещать отмену стоимости или provider обработки.
[Java21 HttpClient](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html).
Для MVC использовать явно настроенный timeout/executor и callbacks, не default container timeout.
[Spring async](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html).

## File map и AC/test seams

| AC | Файлы будущей реализации | Проверка |
|---|---|---|
| BAI-001 auth/config/manual fallback | controller/ai/AiController.kt, controller/model/AiModels.kt, config/AiConfiguration.kt, application.yml | real HTTP unauth 401; unconfigured status 200/actions []; action503; fake provider 0calls; legacyhealth/sync unaffected |
| BAI-002 owner freshness | service/ai/AiContextReader.kt, AiActionService.kt; existing auth/head/catalog repos | A/B shared UUID refs cannot leak; revision mismatch before provider; mutation/catalog archive during barrier-controlled call rejects response; revoke/delete account race; no locks held during fake wait |
| BAI-003 server prompt/validated draft | service/ai/AiProvider.kt, HttpAiProvider.kt, AiDraftValidator.kt | handwritten provider fake sees only authorized context; unknown fields/enums/NaN/wrong UUID/empty OCR reject; valid Existing/New/InBody → no records/head/operation count changes |
| BAI-004 image/lifetime/errors | service/ai/AiImageInput.kt, AiActionService.kt, Errors.kt only necessary safe mappings | exact max and max+1, missing/false Content-Length chunked cap, invalid base64/nonJPEG/dimensions, slow headers/body, oversized provider body, disconnect/deadline, no leaked temp files/PII errors |
| BAI-005 deployment docs | .env.example, docs/api.md, generated docs/openapi.json, README.md; infra/env delivery only ifneeded | default off; no literal secret; compose config quiet with dummy vars; isolated fake HTTP provider contract tests; no real call in CI |
| BAI-006 compatibility | BackendIntegrationTest.kt or dedicated AiIntegrationTest.kt + test provider config | current sync v2/v3/capabilities unchanged; no new kinds, no migration unless a later agreed durable store is justified |

Один shared fixture для точных DTO/nullable segments нужен до параллельной реализации. Основной
HTTP test stack уже JUnit5+Spring random-port+Testcontainers PostgreSQL; добавить in-process
fake HTTP provider для malformed/timeout/size/cancel adapter tests и handwritten domain fake
для ownership/revision races. Gradle/repo writes в этом исследовании не запускались.

## Что ещё обязательно закрыть перед Gate P / production

- Gate P должен принять описанный exact DTO в shared fixture с Android и strict draft validation,
  согласовать выше suggested bounds/errors/status endpoint и Ready(owner,revisions) handshake.
- Выбор реального provider/model/vision/JSON schema support и разрешённой среды, подтверждение
  передачи выбранного фото; никаких догадок из старого Android BYOK. Provider official docs
  проверяются при выборе; локальный BYOK key не мигрировать и не читать.
- Существующий deploy уже принимает env_file, но workflow переносит SMTP-specific JSON, AI secret
  delivery ещё отсутствует. Следующий implementation plan должен добавить env-only secure delivery
  по существующему stdin/temporary-file + cleanup pattern, с dummy test и без echo секретов.
- Доступ GitHub publication/production credentials сейчас root отметил заблокированным правами.
  Research/implementation можно продолжать локально; live AI и deployment readiness не подтверждены.
