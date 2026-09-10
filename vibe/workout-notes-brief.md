# #9 — заметки тренировки/подхода и личные закреплённые подсказки упражнения

## Status

**pass.** Решение владельца от 10.09.2026: заметки принадлежат фактической тренировке и её
подходам; закреплённая подсказка принадлежит только владельцу и конкретному упражнению, живёт до
явного открепления. `STANDARD` не меняется. Ниже зафиксированы реализации, а не новые вопросы.

## Scope и non-goals

Входит:

- одна редактируемая заметка тренировки и одна на каждый фактический подход, в активной сессии и
  её неизменяемом историческом снимке;
- одна личная закреплённая текстовая подсказка на упражнение для текущего владельца; она видна при
  выполнении этого упражнения и в его detail, переживает тренировки/устройства, удаляется только
  явным «Открепить»;
- перенос через тот же `workout` aggregate и существующий `BackendSync`/outbox, плюс отдельный
  portable kind для личной подсказки.

Не входит: заметка программы, несколько заметок/теги/файлы, совместное редактирование, health
данные, автоматическая интерпретация текста, тренерский доступ, новая вкладка, новый Gradle-модуль
или зависимость. AI получает заметку только как типизированные датированные данные будущего
согласованного контекста; свободный текст не является инструкцией, не даёт разрешений и не
подменяет текущие фактические показатели/усталость.

## Candidate acceptance criteria

- **AC-001.** Пользователь может в активной тренировке создать, заменить или удалить заметку
  тренировки и заметку каждого подхода; запись сразу переживает поворот и смерть процесса после
  Room commit. Пустой после `trim()` текст означает удаление. Максимум — **2 000 Unicode code
  points** для каждого поля; превышение не сохраняется и показывается как доступная ошибка поля.
- **AC-002.** Заметка подхода относится к `WorkoutSetEntity.id`, а не к номеру подхода или
  упражнению: добавление, перестановка и повтор одного упражнения не переносят её на другую
  строку. Удаление подхода/упражнения/тренировки каскадно удаляет её.
- **AC-003.** История показывает тот же снимок заметки тренировки и выполненных/невыполненных
  подходов. Закрытие тренировки не копирует и не очищает заметки; «сохранить как программу» не
  переносит их в `RoutineEntity.note`.
- **AC-004.** Для личного или `STANDARD` упражнения можно установить одну личную подсказку.
  Создание/правка подсказки никогда не обновляет `ExerciseEntity`, его `origin`, `syncId`,
  мышечную карту или требования оборудования. Открепление удаляет только personal-hint record.
- **AC-005.** Активная карточка упражнения показывает личную подсказку до первого действия с
  подходом и даёт семантическое действие редактирования/открепления; detail упражнения даёт то же
  состояние. На compact/medium/expanded, fontScale 2.0 и TalkBack действия остаются достижимы.
- **AC-006.** `workout` wire payload round-trips оба note-поля с новым и старым клиентом: старый
  клиент игнорирует неизвестные поля на сервере и не должен стереть их при последующей правке;
  новый клиент читает их как пустые, если серверный старый снимок их не содержит.
- **AC-007.** `exercise_hint` — owner-scoped portable record с id стабильного exercise `syncId`.
  Он не выгружается для guest/чужого owner, не попадает в catalogue sync и не делает `STANDARD`
  personal. Sync повторяем и не создаёт дубликаты.
- **AC-008.** При конфликте *одной и той же исторической тренировки* серверный snapshot побеждает
  целиком, в том числе заметки тренировки/подходов. При конфликте `exercise_hint` серверный
  snapshot побеждает; запись с `deleted=true` также побеждает. Конфликт подсказки не создаёт две
  программы и не затрагивает workout/routine/exercise payload.
- **AC-009.** Пока есть активная тренировка, входящий workout snapshot по-прежнему не применяется;
  после завершения обычный sync применяет его атомарно. Локальная правка заметки во время HTTP
  остаётся dirty, потому что baseline содержит именно подтверждённый payload.

## Текущий execution/data flow и доказательства

`ActiveWorkoutScreen → ActiveWorkoutViewModel → ActiveWorkoutRepository → WorkoutDao → Room` уже
является SSOT активной сессии: `WorkoutDao.observeActiveWorkout()` отдаёт `WorkoutFull`, а
`ActiveWorkoutRepositoryImpl.observeActive()` сортирует его. `WorkoutSetMutator` — единственный
process writer чисел и completion; заметки не должны обходить его для изменения set-чисел или
completion. Отдельные узкие DAO updates могут писать только text-поля, с проверкой, что set всё
ещё принадлежит ожидаемой workout.

`WorkoutEntity` уже содержит `note`; `WorkoutSetEntity` не содержит заметки. `WorkoutFull` →
`WorkoutExerciseWithSets` формирует исторический snapshot. `WorkoutDetailViewModel.load()` уже
передаёт `full.workout.note`, а `WorkoutDetailScreen.NoteCard` показывает непустое значение,
поэтому история требует расширить view state для заметок set, а не второй запрос/кэш.

`PortableData.snapshot()` строит один `workout:<UUID>` aggregate с exercise section (`sectionId`) и
sets. `PortableData.apply()` заменяет только дочерние rows этого snapshot в Room transaction.
`BackendSync.run()` сравнивает aggregate с `backend_baseline`, durable сохраняет exact push в
`backend_outbox`, запрещает apply активной тренировки и затем применяет/acknowledges снимки.
Следовательно notes должны быть полями существующего workout aggregate; отдельный notes outbox,
DataStore кэш или второй sync worker нарушит этот контракт.

У упражнений есть device-independent `ExerciseEntity.syncId` и immutable catalog ownership
`origin`; `ExerciseDetailViewModel.openEditor()` и `ExerciseLibraryScreen` запрещают mutate
`STANDARD`. Личная hint therefore needs a narrow owner-local table keyed by `exerciseSyncId`, not
a column mutated on `ExerciseEntity`. This is a domain record carried by `PortableData`, not a
parallel storage/sync pipeline.

## Data contract, migration и конфликт

Исследованный baseline Room — v16. После этапов 08/12/CAL-01 нужен следующий номер фактически
интегрированной схемы, ручная миграция и соответствующий exported JSON, а не повторная v17:

1. Add `workout_sets.note TEXT NOT NULL DEFAULT ''`.
2. Create `exercise_personal_hints` with `exerciseSyncId TEXT PRIMARY KEY`, `text TEXT NOT NULL`,
   `updatedAt INTEGER NOT NULL`. It contains no FK to local numeric exercise id: catalog/owner
   import may remap local IDs, while `syncId` is portable. Add an index only if a query requires
   it; primary-key lookup covers the active screen.
3. Extend `SyncSchema.trackedTables` with `exercise_personal_hints`, so the existing generation
   trigger schedules `BackendSync`; do not create another observer/outbox/worker.
4. Add `ExercisePersonalHintDao` to `GymDatabase`; its observe query joins by `syncId` (or the
   repository maps current exercise to its `syncId`). DAO writes validate normalized text and
   monotonic `updatedAt`; delete is an explicit row delete. Workout/set note updates are DAO
   queries constrained by `workoutId` and mutate only text.

Wire additions:

- In `workout` payload, `note` remains workout note and every set object adds `note`. These fields
  are required for v-next serialization and optional/default-empty when decoding old snapshots.
- Add top-level kind **`exercise_hint`**, id equal to `exerciseSyncId`, payload
  `{ "text": String, "updatedAt": Long }`. It is ordered after `exercise` on upsert and before
  `exercise` on delete only if server enforces referential ordering; Android apply itself requires
  no local exercise FK. Include it in `PortableData.snapshot/apply`, `BackendSync` order and
  server supported-kind/capability negotiation.
- The server must preserve unknown newer workout fields when accepting an older client payload,
  or reject it with a version/capability error; blind whole-JSON replacement would erase notes.
  Android must not advertise/send `exercise_hint` until the server advertises it. A server with no
  such capability leaves local notes/hints durable and marked unsynced; it must not report healthy
  sync nor ACK/drop them.

The established Stage-12 rule is selected: for workout/history conflict use the **server** aggregate
snapshot. This deterministically settles set notes too. For hint conflict use server snapshot as
the single configuration-like record. Explicit delete has normal tombstone semantics: a remote
delete wins the same-key conflict; a later fresh local edit creates a new push from no baseline,
not an implicit resurrection. Routine two-version conflict behavior does not apply to either kind.

## Affected files and layers

| Layer | Files / symbols |
|---|---|
| Room | `data/db/entity/WorkoutSetEntity.kt`, new `ExercisePersonalHintEntity.kt`, `data/db/dao/WorkoutDao.kt`, new `ExercisePersonalHintDao.kt`, `data/db/GymDatabase.kt` (`version`, entity list, DAO, `MIGRATION_16_17`) |
| Domain/data | `domain/ActiveWorkoutRepository.kt`, `data/ActiveWorkoutRepositoryImpl.kt` for typed edit APIs; a small hint repository/use case bound in `di/DataModule.kt`/`di/DomainModule.kt` |
| Sync | `data/backend/SyncSchema.kt`, `PortableData.kt`, `BackendSync.kt`, backend contract/server supported kinds; no new worker |
| Active UI | `ui/active/ActiveWorkoutViewModel.kt`, `ActiveWorkoutScreen.kt`: reactive hint joined to active `WorkoutFull`; saveable editor draft, single in-flight submit, one-shot error |
| History | `ui/history/WorkoutDetailViewModel.kt`, `WorkoutDetailScreen.kt`: show immutable workout/set note snapshot |
| Exercise UI | `ui/exercise/ExerciseDetailViewModel.kt`, `ExerciseDetailScreen.kt`: personal hint editor/clear action while retaining `STANDARD` catalog guard |
| Tests/schema | `app/schemas/.../17.json`, `data/db/Migration16To17Test.kt`, `data/WorkoutDaoTest.kt`, `data/BackendSyncTest.kt`, `ui/ActiveWorkoutViewModelTest.kt`, `ui/active/ActiveWorkoutScreenTest.kt`, history/detail tests as needed |

## UI and lifecycle contract

Use an existing M3 text-field dialog/bottom sheet pattern with explicit Save and Delete/Unpin.
Save normalizes `trim()`, rejects 2,001+ code points before DAO call, and disables duplicate submit;
Delete asks confirmation only after persisted nonempty content. Do not autosave while typing. Keep
the draft, validation error and request token in `SavedStateHandle`; on recreation re-read Room and
discard a draft whose target set/workout/hint no longer exists. A late coroutine must check that its
token/target still matches before displaying feedback. Room Flow remains the displayed truth.

Use `GymCard`, `MaterialTheme.colorScheme`, `GymMotion` for any visibility transition, and
`gymHaptics()` semantic tap/success/error. Every icon action has a label; hint text is selectable
or exposed as normal `Text`, never color-only. Actions are 48dp minimum and must wrap/reflow at
fontScale 2.0. Do not add a new navigation route: active workout, history detail and exercise
detail are existing destinations.

## Invariants and Android-specific risks

- A finished workout is a history aggregate; later program save/replacement, catalog edits and
  hint changes cannot rewrite it. A note is not input to analytics, PRs, rest timing or progress
  calculations.
- The foreground service and `WorkoutSetMutator` retain their single-writer guarantee. Note save
  cannot race into a different set after exercise reorder because it targets persistent set ID.
- Sync cancellation/worker death leaves committed Room changes and `backend_state.generation`;
  restart observes dirty tables. No upload scheduling during an active workout can apply a remote
  snapshot. `START_NOT_STICKY` remains unchanged.
- Hint ownership is account-local. `BackendSync.clearAccountData()` must clear it on owner switch,
  account deletion and personal-cache reset. Guest data remains locally usable but is not sent
  until the existing Stage-12 claim/merge flow authorizes it.
- There is no verified server support for health, trainer or sharing endpoints. Notes/hints must
  remain private to the account; do not infer trainer readability or health classification from
  their text. Future trainer disclosure needs explicit scope/consent/contract, not this kind.

## Recommended verification

- `Migration16To17Test`: v16 fixture with workout/set rows migrates default notes to `''`; hint
  table/PK and all v16 data survive; schema export matches.
- DAO/repository: Unicode boundary 2,000/2,001, whitespace delete, set/workout ownership guard,
  cascade deletion, hint monotonic timestamp and standard exercise unchanged.
- ViewModel/Compose: live collector pattern, recreation draft, concurrent double-save, deleted
  target, set ordering/duplicate exercise, TalkBack labels and font-scale layout semantics.
- Sync fake: portable snapshot/apply round-trip preserves both notes; active workout blocks pull;
  server-wins conflict applies both note levels; existing local edit after captured outbox remains
  pending; hint create/update/delete is idempotent; old payload defaults safely; unsupported kind
  cannot be ACKed/dropped; owner switch clears hints.
- Server integration gate: old/new client round-trip preservation and capability response, all
  accepted kinds (`exercise`, `gym`, `routine`, `workout`, `measurement`, `schedule`,
  `exercise_hint`), plus explicit absence of health/trainer/share grants. Full Android unit tests
  and debug assembly only after implementation changes.

## Root уточнения до Gate P

- Завершение сейчас удаляет незавершённый set при всех пустых числах. Заметка является данными:
  после появления set.note условие пустоты должно также требовать пустую заметку. Подход только
  с заметкой остаётся в истории, но не считается выполненным и не попадает в сохранение программы
  по выполненным подходам. Покрыть отдельной регрессией.
- Legacy-протокол нельзя оставлять выбором preserve/reject. План должен зафиксировать точное
  сохранение note по стабильным IDs либо узкий отказ изменения конкретного аннотированного
  aggregate старым клиентом. Blanket account-v4 запрещён; старый round-trip не должен терять notes.
- Backend уже имеет три CAL-01 kinds помимо шести legacy. Их совместимость сохраняется.
- Упоминания Migration16To17 ниже/выше являются исследовательскими примерами: исполнитель
  заменяет их соседней и полной миграциями к фактической следующей схеме после зависимостей.

## Official sources used

none — no platform or library uncertainty required external research.

## Files changed

`vibe/workout-notes-brief.md` only.
