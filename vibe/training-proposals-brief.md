# Gate R: PLAN-01 — общие предложения тренировок (#53 / #42)

Дата: 2026-09-10. Статус: **pass**. Это общий механизм предпросмотра и применения для календарного AI (этап 21) и человека-тренера (этап 23). Он не реализует связь тренера, поиск, социальную сеть, health-контекст или AI-provider.

## Scope и границы

Предложение — server-owned, versioned document с явными author и recipient, которое показывает получателю редактируемый локальный preview и меняет его данные только по одному явному approve. Reject/revoke/stale не создают программу, календарный план или запись в историю. AI и тренер создают одинаковый domain proposal, различаясь только `source` и полномочием автора.

В scope: immutable version snapshots, pending/approve/reject/revoke/stale state machine; one-off proposal, из которого recipient применяет новый personal routine и calendar plan; idempotency/recovery; server authorization hooks для будущего тренера. Не входят: прямое редактирование чужих программ/календаря, изменение выполненной истории, weekly series (этап 25), доступ тренера к health/profile/notes, live chat, nutrition, OCR и автоматическое применение.

`BackendCoachJournalService` не является этим контрактом: это self-account Live Coach immutable journal с собственным version-3 guard. Его нельзя использовать как relation/permission/proposal storage или выдавать через него доступ другому человеку.

## Candidate acceptance criteria

- **AC-001.** Сервер хранит предложение с `proposalId` UUID, `authorAccountId`, `recipientAccountId`, `source = AI | COACH`, `status = PENDING | APPROVED | REJECTED | REVOKED | STALE`, `currentVersion`, `createdAt`/`updatedAt` epoch-ms UTC и immutable payload snapshot для каждой версии. `recipientAccountId` всегда обязателен; AI получает отдельный server actor ID, не подставляет recipient как author.
- **AC-002.** Создание разрешено только серверному AI для текущего authenticated recipient либо COACH author, имеющему активное разрешение связи с этим recipient. Чтение списка/детали разрешено author и recipient в пределах роли; approve/reject доступны только recipient; revoke — только author до approve. Разрыв/отзыв связи немедленно прекращает будущие чтение/создание/approve по COACH proposal. Это hook этапа 23, не неявное разрешение на профиль или health.
- **AC-003.** Payload версии содержит только планируемую программу и one-off calendar plan: название, note, selected gym UUIDs, ordered exercises by **canonical exercise UUID**, planned sets and nullable `weightKg`, rest, start instant плюс IANA zone. Local database IDs, body measurements, raw prompt, health documents и credentials в payload не попадают. Неразрешимый UUID, неизвестное оборудование, некорректная структура/параметры или expired/stale context отвергаются до preview.
- **AC-004.** Preview локально редактируемый, но не изменяет server proposal и не создаёт Room records. Редактирование образует `approvalDraft` для текущей immutable version; UI всегда показывает author/source/version и факт локальных изменений. Новый author edit создаёт следующую version snapshot, прежний preview становится stale и требует повторного чтения/подтверждения.
- **AC-005.** `approve(proposalId, version, operationId, approvalDraft)` выполняется сервером exactly-once для recipient: проверяет PENDING/current version, author permission, current context and canonical references, затем atomically фиксирует APPROVED version and the accepted result keyed by UUID `operationId`. Повтор с тем же operationId возвращает тот же result; другой operationId после approve/reject/revoke/stale не создаёт вторую программу/план.
- **AC-006.** После successful approve Android атомарно materializes exactly one **new personal** `RoutineEntity` and one calendar plan from accepted result, со свежими sync UUIDs/updated timestamps; результат не перезаписывает existing routine и не зависит от completed-workout history. До successful approve в `routines`, calendar tables, `PortableData` и BackendSync нет записи предложения.
- **AC-007.** `reject(proposalId, version, optionalReason)` — recipient-only terminal action, идемпотентная для той же версии и без локальной программы/плана. Причина — user data, не инструкция AI; она видна только участникам предложения и не создаёт тренерского доступа за его пределами.
- **AC-008.** AI планирование сначала завершает обязательную backend sync readiness; при guest/unauthorized, offline/timeout, cancellation, busy, owner change, missing consent, stale context или validation failure proposal не создаётся и ручные calendar/routine paths сохраняют работоспособность. Missing relevant exercise history leaves proposed `weightKg = null`; не подставляются веса из другого упражнения.
- **AC-009.** Process death between server approval and local materialization recovers by reissuing/reading the same `operationId` result and committing local routine+plan once. Local Room transaction, BackendSync owner mutex/outbox, active-workout guard and later sync preserve no-duplicate semantics.
- **AC-010.** У получателя есть distinct actions «Применить», «Отклонить» и Back/dismiss (не решение). Preview speaks status/version/source and change effects to TalkBack, works at fontScale 2.0 and compact/medium/expanded; no colour-only status or automatic navigation into active workout.

## Contract and ownership

Server is the source of truth for proposal authorization, versions, decisions and approval idempotency. It exposes create/list/read/revoke and recipient `approve(id, version, operationId, approvalDraft)` / `reject(id, version, reason?)`; `approvalDraft` is normalized and validated as a planned routine + one-off plan before the terminal state is written. Every author modification produces `version + 1` and retains the previous immutable snapshot. A proposal is `STALE` when the authoritative prerequisites changed before decision (including current version replacement, revoked author relation, recipient ownership/context barrier, or canonical reference invalidation); no automatic substitute is made.

Android owns only transient draft editing and durable recipient projections after an accepted result. The projection boundary should be a dedicated `TrainingProposalRepository` / `ApplyTrainingProposalUseCase`, not `SaveCompletedWorkoutAsRoutineUseCase`: the latter maps completed historical sets to a gym-unrestricted routine and deliberately omits incomplete sets (`domain/SaveCompletedWorkoutAsRoutineUseCase.kt:28-75`). Its transaction/idempotency pattern is useful, but its history semantics are not.

The accepted result names canonical UUIDs, never device-local `exerciseId`. Android resolves them through `ExerciseDao`/the canonical catalog after catalog readiness; all result exercises must resolve before a Room transaction begins. The transaction inserts a new `origin = PERSONAL` routine aggregate and one calendar plan referencing it. It uses an operation-result journal or a unique persisted projection key to make replay idempotent, then schedules the normal configuration/calendar sync only after commit. A failed scheduling call cannot undo the durable projection or cause a second apply.

The future relationship contract for stage 23 is minimal and explicit: `coach_relationship` is owner-scoped, invitation/acceptance/revocation based, and grants COACH read of calendar plus existing workouts only. Its capability is checked server-side on every proposal operation. It grants no health/profile/notes visibility, no client-side privilege and no direct write; a separate recipient approval remains mandatory for every proposed plan.

## Current execution/data flow (evidence)

1. Current configuration is local Room SSOT. `RoutineEntity` carries a device-independent UUID `syncId`; `RoutineDao.getRoutineBySyncId()` is already a create-operation replay lookup (`data/db/entity/RoutineEntity.kt:8-20`, `data/db/dao/RoutineDao.kt:43-64`).
2. The existing `SaveCompletedWorkoutAsRoutineUseCase` builds a routine draft then delegates validation and an atomic transaction to `GymRepository`; after commit it schedules upload and treats scheduling failure as recoverable, while rethrowing cancellation (`domain/SaveCompletedWorkoutAsRoutineUseCase.kt:28-106`). Reuse that durability model only.
3. Current ad-hoc calendar rows join to an existing routine and are inserted independently by `ScheduledWorkoutDao` (`data/db/dao/ScheduledWorkoutDao.kt:13-32`). CAL-01 changes the durable calendar graph, so PLAN-01 must call its future CalendarPlanRepository shared choke point rather than couple to this legacy DAO.
4. `PortableData` converts routine and workout aggregates to portable records using `syncId`; BackendSync owns snapshot/revision/outbox processing. It already prevents unsafe sync around an active workout and is called by `BackendUploadAdapter` (`data/backend/PortableData.kt:60-176`, `data/backend/BackendSync.kt:200-357`, `data/backend/BackendUploadAdapter.kt:17-36`). Proposals are server documents and do not appear as local PortableData records before approval.
5. `CanonicalExerciseRegistry` establishes stable built-in exercise UUIDs and reconciliation. This is the required reference type for a cross-device/server proposal, never an auto-increment Room ID (`data/db/CanonicalExerciseRegistry.kt:206-214, 499-505`).
6. The global plan assigns AI-01 the server context/provider/validator and PLAN-01 preview/application; it explicitly requires sync before AI planning and says a new program exists only after consent (`vibe/github-issues-implementation-plan.md:55-59, 86-105`; `vibe/github-issues-implementation-plan-track.md:110-129`).

## Affected layers and files

Future server work: typed proposal and relationship persistence/migration, authorization service, AI proposal creator, proposal controller/DTOs, idempotent operation ledger and tests. Future Android work: proposal API/repository/DTOs, draft/preview ViewModel and Compose surface, a durable projection journal/use case, CalendarPlanRepository integration, routine/calendar transaction support, Hilt bindings, navigation, and tests. Existing `PortableData`, `BackendSync`, routine/calendar entities and schemas are integration points; their exact Room version and CAL-01 tables must be taken from the actual predecessor at implementation time.

## Invariants and risks / verification

- **Authorisation/privacy:** test recipient A, author coach B, unrelated C, guest and revoked relation against every endpoint; prove health/profile/notes are absent from both payload and authorization. Never infer relation from a journal, email or client parameter.
- **Atomicity/retry:** test approve double tap, same/different operation IDs, response loss after server commit, process death before/after Room transaction, stale version, reject/revoke race and owner switch. Assert one personal routine and one plan at most, no proposal-induced workout history.
- **Validation/context:** malformed UUID/order/set data, unknown canonical exercise, unavailable equipment, no relevant history (null weight), active workout, sync error and late AI response all leave no partial proposal/projection. Backend validates before preview and again on approve.
- **Concurrency/sync:** exercise catalog transition and CAL-01 graph conflicts are checked at current version; transaction failure rolls back both local routine and calendar plan. Outbox recovery may retry sync, never approval creation.
- **UI/accessibility:** editable preview has explicit version/stale state and actions; user edits survive configuration change, not necessarily process death until a dedicated draft journal exists; messages distinguish retry, stale, rejected and applied. Use existing Material/GymMotion/GymHaptics policies.

## Assumptions and open product decisions

Safe delegated defaults are: single one-off plan per approved proposal; terminal reject/revoke; explicit new version for author edits; default null proposed weight without relevant same-exercise history; and human relation as a later separate permission gate. The current plan leaves history-window/load sufficiency, proposal expiry period, reason retention/export, recurrence and exact UI location open. None blocks this common contract because stale/version handling avoids silently deciding them.

## Official sources used

none — no platform or library uncertainty was encountered.

## Files changed

`vibe/training-proposals-brief.md` only.
