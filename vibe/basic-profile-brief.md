# Gate R: базовый профиль и общий контракт здоровья (#50-A/#50-B, этапы 14–15)

Дата исследования: 2026-09-10. Статус: **pass**. Решения D-003 и согласованный набор первой версии уже достаточны для реализации; этот brief не открывает OCR, питание, клинические документы или рекомендации.

## Scope и non-goals

Этап 14 фиксирует один переносимый контракт личного контекста для профиля и будущих чувствительных данных. Этап 15 добавляет добровольный базовый профиль: цель, пол, дата рождения (как источник возраста), тренировочный опыт, планируемая недельная частота, желаемая длительность занятия, предпочтения оборудования и свободно введённые тренировочные ограничения. Он доступен в настройках всегда и предлагается только перед AI-действием по D-003.

Не входят: второй источник веса/InBody/обхватов; OCR, PDF/фото, лабораторные результаты, диагнозы, лекарства, травмы либо клинические пороги; питание и добавки; тренерский доступ; новый модуль, dependency, Android permission, foreground service или отдельный worker. AI не даёт медицинских советов и не интерпретирует свободный текст как диагноз.

## Candidate acceptance criteria

- **AC-001.** Профиль доброволен: пустой профиль валиден, ручной учёт и AI остаются доступны без него; каждое незаполненное поле имеет значение `unknown`, а не выдуманный default.
- **AC-002.** Владелец может в любой момент создать, изменить либо очистить каждое поле профиля. `birthDate` вводится как точная локальная календарная дата, возраст вычисляется на момент использования; в AI передаются только вычисленные полные годы и только если дата есть.
- **AC-003.** Допустимые кодированные значения стабильны: `trainingGoal` = `STRENGTH | MUSCLE_GAIN | FAT_LOSS | GENERAL_FITNESS | ENDURANCE | OTHER`; `sex` = `FEMALE | MALE | PREFER_NOT_TO_SAY`; `experienceLevel` = `BEGINNER | INTERMEDIATE | ADVANCED`. `null` означает unknown. Никакой вывод пола, возраста или опыта из тренировок/InBody не допускается.
- **AC-004.** `plannedSessionsPerWeek` — nullable целое 1…7; `preferredSessionDurationMinutes` — nullable целое количество минут; допустимые equipment IDs берутся только из локального канонического каталога. Пустой набор оборудования означает unknown/no preference, а не «доступно всё». Реальная доступность упражнений остаётся за выбранными залами, `gym_equipment` и `EquipmentCoverage`.
- **AC-005.** `manualConstraints` — nullable пользовательский текст, не структурированная медицинская запись и не триггер клинических правил. Он показывается/посылается в AI только как явно сохранённый контекст; отсутствие текста не заменяется на «нет ограничений».
- **AC-006.** Контракт синхронизации содержит один self-contained профильный record, с UUID `syncId`, временем пользовательского изменения `updatedAt` в epoch milliseconds UTC и массивом equipment IDs. Синхронизация происходит только для авторизованного владельца через существующий `BackendSync`; у тренера, Google Sheets и прочих клиентов нет неявного доступа.
- **AC-007.** `body_measurements` остаётся единственным хранилищем веса, состава тела, InBody и обхватов; профиль хранит ни одного их значения и не создаёт вторую health store.
- **AC-008.** Перед действительным AI-запросом может появиться краткое предложение заполнить профиль с объяснением пользы для планирования. После показа `lastAiProfilePromptAt` фиксируется; повтор возможен только при следующем AI-входе через **72 часа** или позже. «Продолжить без заполнения» запускает исходное AI-действие; «Не предлагать» навсегда отключает предложение, пока пользователь не включит его вновь в профиле. Никакое предложение не прерывает тренировку.
- **AC-009.** Профиль, prompt-state и синхронизация переживают configuration change/process death без повторного запроса или двойной отправки. Отмена AI, offline/error и смена владельца не изменяют профиль и не обходят consent.
- **AC-010.** Room обновляется только ручной миграцией с экспортированной schema и migration-test; состояние prompt-а хранится в существующем DataStore. Текущая схема Room — v16, поэтому при сохранении текущего порядка этапов профильная миграция — **v16 → v17**; номер нужно перепроверить против интеграционной ветки непосредственно перед реализацией.

## Предлагаемый data contract и владельцы

`ProfileEntity` — Room singleton `id = 1`, владелец локального профиля и переносимого snapshot-а:

| Поле | Тип / единица / unknown | Источник и правило |
|---|---|---|
| `id` | `Int = 1` | технический singleton, не внешний идентификатор |
| `syncId` | UUID string | создаётся локально один раз, stable cloud identity |
| `trainingGoal` | nullable stable enum | только явный выбор владельца |
| `sex` | nullable stable enum | только явный выбор владельца; `PREFER_NOT_TO_SAY` — явный выбор, `null` — unknown |
| `birthDate` | nullable ISO-8601 `YYYY-MM-DD` | точная введённая локальная дата; возраст не хранится и вычисляется по локальной дате запроса |
| `experienceLevel` | nullable stable enum | только явный выбор владельца |
| `plannedSessionsPerWeek` | nullable integer, sessions/week | явный план, 1…7 |
| `preferredSessionDurationMinutes` | nullable integer, minutes | явный план; не длительность уже выполненных тренировок |
| `manualConstraints` | nullable trimmed text | личный тренировочный контекст; не clinical `health_constraint` |
| `updatedAt` | epoch milliseconds UTC | меняется только при содержательном пользовательском сохранении |

`ProfileEquipmentPreferenceEntity(profileId: Int = 1, equipmentId: String)` — дочерний набор без дубликатов. `equipmentId` валидируется против `LocalEquipmentCatalog`; пустой набор означает unknown/no preference. Он не заменяет ни `GymEquipmentEntity`, ни `ExerciseEquipmentEntity`.

Строки Room и оба дочерних набора собираются в единственный PortableData record `{ kind: "profile", id: syncId, payload: { schemaVersion: 1, ... } }`; payload включает все nullable поля явно как JSON null либо отсутствует по единому текущему PortableData соглашению, equipment IDs сортируются детерминированно. `updatedAt` и `syncId` остаются в payload. При pull применяется atomically: singleton + весь дочерний набор в одной Room transaction. `SyncSchema.trackedTables` добавляет обе таблицы; изменение дочернего набора invalidates тот же profile snapshot. `PortableData.records/apply` и `BackendSync` добавляют kind `profile` до зависящих profile records (таких в этом этапе нет). Сервер обязан принимать это как additive typed record и привязывать к authenticated owner; backend schema/endpoint не меняются неявно данным Android этапом.

Будущие `health_observation`/`health_constraint` получают собственные record kinds, revision/consent/tombstone policy и отдельный продуктовый Gate R. Они могут ссылаться на тот же owner/sync infrastructure, но не являются колонками `ProfileEntity` и не переносят `BodyMeasurementEntity`.

`ProfilePromptState` принадлежит `SettingsRepository`/существующему `settings` DataStore, а не Room и не облачному profile record: `ai_profile_prompt_disabled: Boolean = false`, `ai_profile_last_prompt_at_epoch_millis: Long? = null`. Время берётся из инъецируемой/тестируемой clock boundary, а не из Compose; показывать можно только если disabled=false, профиль неполон, и `now - last >= 72h`. Timestamp пишется при фактическом показе prompt (включая skip/disable), а не при попытке открыть AI или при сетевом результате. Это делает D-003 единым для всех будущих AI entry points и устойчивым к recreation.

## Текущий execution/data flow (evidence)

1. Compose settings root routes category to `AccountCard`/connection cards (`ui/settings/SettingsScreen.kt:168-213`); settings state comes from `SettingsViewModel.uiState`, which combines `SettingsRepository.settings` using `stateIn(WhileSubscribed)` (`ui/settings/SettingsViewModel.kt:151-181`). A Profile settings category/card should reuse this unidirectional flow and the design-system settings card pattern, not create an account identity.
2. `SettingsRepository` maps `settings` DataStore into `GymSettings` and offers atomic `edit` mutators (`data/settings/SettingsRepository.kt:21-201`); it is the appropriate owner for only D-003 local prompt preference/timestamp, not for syncable profile content.
3. Existing manual/InBody data enters `MeasurementEditorViewModel`, persists `BodyMeasurementEntity` through `BodyMeasurementDao`, then schedules upload (`ui/measurements/MeasurementEditorViewModel.kt:174-189`). `body_measurements` has nullable metric columns and `measuredAt` (`data/db/entity/BodyMeasurementEntity.kt:7-64`), preserving missing values rather than zero.
4. Global backend sync tracks `body_measurements` (`data/backend/SyncSchema.kt:24-65`) and `PortableData` converts it to the `measurement` record (`data/backend/PortableData.kt:161-162, 337-347`). This is the existing reusable transport boundary; no separate health database or per-profile upload worker is needed.
5. `BackendUploadAdapter.uploadMeasurement()` delegates into the same authenticated, active-workout-aware `BackendSync.run()` (`data/backend/BackendUploadAdapter.kt:17-36`). Profile sync should use that pipeline and its owner/outbox/revision barriers, never direct Sheets transport.
6. A current AI entry is `AiExerciseCreationSheet` → `ExerciseLibraryViewModel` generator; the sheet only exposes `onGenerate` and remains usable when AI is unconfigured (`ui/library/AiExerciseCreationSheet.kt:46-175`). The D-003 gate must sit immediately before a real request at the shared future AI action boundary, keeping this screen’s cancel/manual path intact. InBody AI remains a separate explicit photo-import draft flow.
7. Room is `GymDatabase` v16 with `ALL_MIGRATIONS`; v14→15 installed backend sync schema and v15→16 catalog fields (`data/db/GymDatabase.kt:65-86, 653-689`). `DataModule` builds Room with all migrations and exposes the singleton settings DataStore (`di/DataModule.kt:67-107`).

## Affected files and layers

Implementation is expected to touch: `data/db/entity/ProfileEntity.kt`, `data/db/entity/ProfileEquipmentPreferenceEntity.kt`, `data/db/dao/ProfileDao.kt`, `data/db/GymDatabase.kt`, `app/schemas/.../17.json`, `data/backend/{SyncSchema,PortableData,BackendSync}.kt`, a profile repository/use-case and Hilt binding in `di/{DataModule,DomainModule}.kt`, `data/settings/SettingsRepository.kt`, a profile ViewModel/screen and settings category in `ui/settings/{SettingsViewModel,SettingsScreen}.kt`, and the shared AI action entry that owns the actual request. Exact backend files remain outside this Android-only brief until its compatible server contract is accepted.

## Project invariants

- One `:app` module; Hilt/KSP; no new dependencies/modules.
- Room is local source of truth, schema is exported, and every production path has a handwritten migration; destructive fallback is forbidden.
- Existing `BodyMeasurementEntity`/`Measurements` and InBody screen/sync stay authoritative and unduplicated.
- Backend sync remains owner-scoped, transaction/outbox/revision protected, and deferred while an active workout exists.
- All UI text stays in Kotlin; Compose uses Material theme colors/shapes, `GymMotion`, semantic `GymHaptics`, ≥48dp controls, TalkBack semantics, fontScale 2.0 and compact/medium/expanded layout policy.
- No `Log.*`, raw health document, or provider prompt/body is persisted or exposed. No implicit trainer, Google Sheets, Google Calendar or account-sharing access to profile/health context.

## Risks and recommended verification

- **Migration/schema:** add a v16→17 `MigrationTestHelper` test seeded with a v16 DB; assert singleton/default unknown profile, FK/index integrity, `PRAGMA foreign_key_check = 0`, pre-existing body measurements/outbox bytes unchanged, and exported `17.json` matches.
- **Sync/concurrency:** unit-test deterministic profile PortableData payload and apply; update both profile fields/equipment together; verify one snapshot after concurrent UI edits; test stale revision, retry, cancellation and owner switch preserve local data/outbox guarantees. Do not enqueue a second profile worker.
- **Lifecycle/process death:** ViewModel test uses a live `uiState` collector; recreate while editing/prompting; verify D-003 timestamp prevents duplicate dialog at 71:59:59 and permits at exactly 72:00:00; disabled survives recreation and re-enable works.
- **Privacy/AI:** fake AI gateway asserts missing fields are omitted/unknown, exact birth date and BodyMeasurement fields never leave the profile context, skip still executes once, disable performs no prompt and no profile write. Explicitly test that no trainer-facing/read API is wired.
- **UI/accessibility:** Compose tests cover empty, partially complete and explicit `PREFER_NOT_TO_SAY` states; TalkBack label/state/action, error text not color-only, 2x font scale, keyboard date/input flow and adaptive widths. Prompt uses a standard dialog/sheet with distinct fill, skip and disable actions; motion/haptics follow existing tokens/semantics.

## Assumptions and product-changing open questions

None blocking this bounded foundation. This brief applies D-003 as fixed in `roadmap-execution-plan-track.md`: 72 hours, skip and disable. The catalog above intentionally treats `OTHER` goal as a stable code without collecting an explanatory free-text goal; adding that text, clinical structured constraints, health consent/sync policy, age threshold policy, or trainer visibility is a separate Gate R.

## Official sources used

none — the proposal follows existing app architecture and accepted project decisions; no platform/API uncertainty required external research.

## Files changed

`vibe/basic-profile-brief.md` only.
