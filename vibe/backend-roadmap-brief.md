# Backend: исследованные контракты глобального плана

Gate R, backend_release_discovery, 10.09.2026. Checkout backend:
`/Users/raul/ItmoProjects/ValerochkaGymBackend`, repo `Valerochka1337/ValerochkaGymBackend`, база dba59ae.

Существуют шесть JSONB kinds exercise/gym/routine/workout/measurement/schedule,
атомарный revision sync, operationId ledger и tombstones. RecordValidator и SQL CHECK ограничивают
kinds. Live Coach — отдельный immutable journal с узким version-3 guard, не API предложений.

## Совместимость

Не вводить blanket v4/account min-version. Новые возможности объявляются optional
`X-Gym-Capabilities`; отсутствие означает legacy. Snapshot/changes скрывают новые kinds от
старых клиентов, не меняя существующий payload. Новые endpoints требуют только свою capability.
Сначала сервер принимает совместимые расширения, затем новый Android их использует.

## CAL-01

Legacy schedule содержит только routineId/dateTimeMillis/calendarEventId; Android идентифицирует
его UUID от calendarEventId и вставляет raw payload в Room. Сохраняем этот контракт. Новому
calendar_plan нужны независимые UUID, recurrence/instance exceptions после фиксации Room-модели.
Связи и tombstones валидируются в существующей sync-транзакции; legacy не получает новый kind.

## PLAN-01 / #42

Нужны server-owned immutable versioned proposal (id/author/recipient/source AI|COACH/status/time),
отдельные отношения/разрешения тренера, create/list/read/revoke и approve(id,version,operationId).
Approval блокирует состояние получателя, проверяет текущую версию/права, атомарно создаёт
routine+calendar_plan и сохраняет идемпотентный результат. Retry возвращает те же записи/revision;
revoked/stale не создаёт частичные данные. Тренер видит только разрешённые календарь/тренировки;
health/profile/notes не открываются автоматически. Live Coach journal не переиспользуется.

## AI-01

В backend нет AI клиента/config/endpoints/context. Java21 HttpClient достаточен без зависимости.
Нужны provider interface, environment-only configuration, timeout/size/error handling,
authenticated action endpoints exercise/InBody/planner и backend-owned context сборка по owner.
Android не отправляет final prompt/API key. Структурная/предметная проверка до preview; файлы
временные с удалением на success/failure/cancellation, никакого содержимого/секретов в логах.
Возвращать только draft/proposal, сохранение предметных данных после явного согласия.

## Profile / health / notes

Новые схемы kinds и additive Liquibase CHECK; observation time/input time/unit/method/source,
correction/version/consent/tombstones заморожены до реализации. Candidate profile,
health_observation, health_constraint, workout_note, set_note, exercise_hint.
Free text трактуется как данные, не инструкция AI или автоматическое разрешение тренеру.

## Guest merge

Отдельный import endpoint не нужен: существующий atomic sync принимает UUID. Android claim
сейчас очищает personal data при первом owner; исправление хранит guest до acknowledgement,
но не считает кэш другого аккаунта гостевым. Исторический snapshot конфликт → server wins;
routine configuration → ровно две полные копии без повторного клонирования на retry.

## Gates

Для каждого backend increment отдельный feature branch/commit, additive migration и HTTP
authorization/validation/idempotency/legacy compatibility tests. CI check+bootJar/admin+Docker,
deploy backup/immutable digest/external health; Liquibase автоматически не откатывается.
Root интегрирует сервер и Android и проверяет финальные release/deploy. Секреты не читать.
