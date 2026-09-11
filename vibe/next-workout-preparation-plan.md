# Фоновая подготовка следующей тренировки

База Android 093a2d7, backend 0d8f9bc. Ветки feat/next-workout-preparation.
Продуктовый контракт принят владельцем; повторное исследование не требуется.

- AC-001 / T-001: явная форма даты/времени/зала/длительности из итогов и календаря; локальная запись до закрытия, без автоматической генерации.
- AC-002 / T-002: Room outbox и WorkManager, точные байты/UUID, bounded backoff, восстановление после process death, owner isolation.
- AC-003 / T-003: durable backend queue с lease/restart recovery, additive POST/GET, идемпотентность и tombstones заменённых запросов.
- AC-004 / T-004: одна карточка календаря со статусами waiting/running/ready/error/stale/expired и предыдущим предложением при пересчёте.
- AC-005 / T-005: существующий редактор, сохранение ручных правок, явное подтверждение и обязательная серверная проверка актуальности; предрасчёт не создаёт планы.
- AC-006 / T-006: offline/ACK loss/reordering/restart/owner/date/invalid result regression tests; Room migration 26→27; доступный прокручиваемый UI.

Владение: основной агент — Android, интеграция и финальные проверки; backend-агент — только /private/tmp/ValerochkaGymBackend-next-workout. Один узкий независимый review после стабильного diff. Скриншоты и prod не используются.

Контракт: POST /v1/ai/calendar-draft-jobs с CalendarDraftRequest и replacesRequestIds (до 1000 UUID), GET /v1/ai/calendar-draft-jobs/{requestId}. Ответ requestId/state/errorCode/result. Состояния QUEUED/RUNNING/READY/FAILED/SUPERSEDED/STALE/EXPIRED. Старый endpoint остаётся совместимым.

Проверки: адресные тесты, затем один полный :app:testDebugUnitTest и :app:assembleDebug; backend check/bootJar. Версия +1 относительно актуального main один раз. Без commit/push/merge/deploy.
