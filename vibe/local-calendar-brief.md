# Gate R: локальные планы календаря (CAL-01)

Root изучил ScheduledWorkoutEntity/Dao, CalendarRepository, WeeklySchedule и
WeeklyScheduleRepository, CalendarViewModel; backend_release_discovery описал server contract.
Продолжить точную схему в Gate P после приёмки guest-sync, не занимая Room writer заранее.

## Требуемое поведение

- AC-001: разовый и недельный план сохраняются в Room без аккаунта, сети и Google grant.
  Local commit — успех планирования; внешняя очередь имеет отдельный статус ожидания/ошибки.
- AC-002: планы и правила имеют устойчивые UUID независимо от Google IDs; timezone и локальное
  время повторения сохраняются. Перезапуск/повтор команды не создают дубль.
- AC-003: existing scheduled_workouts и DataStore weekly rules переносятся без потери привязок
  к программам и Google. Двухсторная Room/DataStore миграция имеет durable completion marker:
  повтор безопасен, исходные данные не удаляются до успешного переноса.
- AC-004: зарегистрированный владелец синхронизирует планы/правила/исключения через backend;
  guest claim включает их. Старые v2/v3 клиенты не получают неизвестные payload/kinds.
- AC-005: исключение одного экземпляра (перенос/отмена) не изменяет правило и остальные даты.
  Факт выполненной тренировки не удаляется отменой/изменением плана.
- AC-006: смена backend owner/Google account и незавершённый legacy weekly recovery journal
  не приводят к исполнению операций для чужого аккаунта или удалению внешних событий.

## Evidence

ScheduledWorkoutEntity: auto Long id, required routineId FK CASCADE, dateTimeMillis,
required calendarEventId. CalendarRepository сейчас делает HTTP insert первым и Room insert
лишь после ответа; offline/guest возвращает NeedsConsent, потерянный ответ может оставить
непривязанное событие. cancel сначала remote delete, затем local delete.

WeeklySchedule — один DataStore JSON; DayRule isoDay/routineId/hour/minute/optional Google ID,
ownerEmail общий. WeeklyScheduleRepository имеет отдельный durable operation journal,
account gate, replace create-new/cleanup-old recovery и background scheduler. Этот журнал нельзя
просто удалить или переиграть после смены модели/owner. CalendarViewModel соединяет finished
workouts, scheduled DAO и WeeklySchedule flow; сохранить UDF, заменить источник плана на Room.

Legacy PortableData schedule UUID выводится из Google ID и raw keys вставляются в Room.
Backend acceptance поэтому additive calendar_plan (или явно раздельные new kinds после freeze),
capability calendar-plans, legacy schedule payload неизменен. Новая модель отделяет local identity,
recurrence/instance identity, Google link/status/revision и backend record revision.

## Границы следующего плана

Gate P должен заморозить точный payload Android/backend вместе, DST/ZoneId правила, UUID migration,
instance original-time key, reference/tombstone policy и owner-bound Google link. CAL-02 добавляет
фактический initial/reverse Google sync; CAL-01 не должен терять действующий legacy recovery.
Месячный UI/nearest/history — отдельный этап09; никакого AI в этом основании.
Один writer владеет Room entity/DAO/migration/schema/portable adapter; server slice отдельный
checkout и отдельный commit после frozen sharedcontract. Tests localoffline/claim/legacy migration
idempotency/activeowner races/weekly exceptions/timezones/backend legacy filtering.

## Дополнение исследователя: legacy recovery и общий payload

GymApplication ставит WeeklyScheduleRecoveryWorker при запуске. До любого Google replay worker
должен проверить migration gate; старый operation journal копируется в owner-bound quarantine,
не исполняется CAL-01. Сохранить accountEmail/ownerEmail, отсутствующего owner не выводить из
текущего Google аккаунта. CAL-02 позднее reconciles только подтверждённого того же владельца.

Предложенные portable kinds: calendar_plan (routineId UUID, startsAtMillis, IANA timeZoneId,
optional legacyScheduleId); calendar_rule (routineId UUID, isoDay, HH:mm localTime, zone,
startLocalDate); calendar_exception (ruleId, instanceKey от original local datetime+zone,
CANCELLED/MOVED, optional moved instant). Reference validation и порядок удаления exceptions
перед rule. Google links — отдельные owner-bound локальные метаданные, не переносимый grant.

DST: wall time в сохранённой зоне; gap — первый валидный instant после gap, overlap — ранний offset.
Instance key остаётся привязан к исходному локальному времени, а не перенесённому instant.

Миграция Room copy с unique source keys → DataStore marker → Room-ready; original schedule/journal
сохраняются read-only. Fault tests на каждой границе. Важная root-коррекция к предложению
исследователя: для уже синхронизированного legacy schedule new plan UUID/source key выводить
из portable legacyScheduleId, а не локального auto-id, иначе два устройства создадут разные планы.
Новые клиенты подавляют matched legacy representation; старые schedule записи не удаляются.
В CAL-02 детерминированная external identity/lookup должна исключить создание двух Google событий
для одного plan UUID с двух устройств, даже если локальная таблица links на втором пуста.
