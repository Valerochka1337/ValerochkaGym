# Live Coach — серверный транспорт

Выбор владельца от 2026-09-11: после перехода main на серверный AI тренер также использует backend. Локальный init-промпт, ограниченный цикл и типизированные бизнес-операции остаются в Android; provider key/URL доступны только backend. Модель тренера выбирается отдельно из серверного allowlist; локальная настройка привязана к аккаунту.

## API

- `GET /v1/ai/coach-models`: `{availability: AVAILABLE|UNCONFIGURED, defaultModel: string|null, models: string[]}`. Доступен только авторизованной сессии.
- `POST /v1/ai/coach-turn`: `{requestId: UUID, model?: string|null, messages: ChatMessage[], tools: FunctionTool[]}`.
- Ответ: `{requestId: UUID, model: string, completion: {choices: [{finish_reason, message: {role, content?, tool_calls?}}]}}`.

UUID связывает ответ с обращением, не запускает backend-операцию и не означает дедупликацию оплаты провайдера. Android никогда автоматически не повторяет POST или инструменты. Сохранённые локальные command/proposal UUID обеспечивают идемпотентность изменений.

Ровно четыре инструмента: `get_workout_state`, `find_exercises`, `get_exercise_history`, `submit_workout_changes`. Сервер не выполняет их, не читает workout/profile/health records и не ожидает SyncReady. Состояние активной тренировки приходит из локального инструмента, поскольку ещё не синхронизировано. На каждой стороне проверяются границы сообщений; бизнес-аргументы и разрешения повторно проверяет приложение.

## Ограничения

512 KiB вход, 80 сообщений, 16 связанных вызовов в истории, строковый контент без изображений, 4 определения tools. Провайдер: один запрос (stream для потоковой ручки), `store=false`, `n=1`, `max_completion_tokens=4096`, `tool_choice=auto`, без `response_format`. Таймаут provider/controller 45 секунд; Android network/agent 60 секунд на обмен и 180 секунд на весь цикл. Android цикл: максимум 6 обменов/16 tools/один пакет или предложение.

Ответ провайдера ограничен 240 KiB; Android envelope — 256 KiB. Сервер отдаёт только текст и allowlisted вызовы, исключая reasoning, usage и прочую метаинформацию. Неизвестные поля входного envelope, дубли JSON и несвязанные tool results отклоняются до провайдера. Глобально два одновременных coach-обмена, не более 30 за минуту на пользователя. Окончательный async HTTP dispatch повторно проходит существующую проверку авторизации backend.

## Настройка сервера

Общие `AI_ENABLED`, `AI_PROVIDER`, `AI_BASE_URL`, `AI_API_KEY`, `AI_TEXT_MODEL`, `AI_VISION_MODEL` сохраняются. `AI_COACH_MODEL` задаёт default тренера (при отсутствии используется AI_TEXT_MODEL); `AI_COACH_MODELS` — дополнительные допустимые IDs через запятую. Всего до 20 IDs, каждый до 200 символов. Модель должна поддерживать function tools в Chat Completions. Проверка из Android использует полностью синтетическую тренировку и не изменяет пользовательские записи.

Протокол 3 и локальная миграция 27→28 поставляются вместе. Серверную ветку нужно развернуть до клиентского выпуска; код не меняет текущую production-конфигурацию и не публикуется автоматически.

Источник wire-формата: [OpenAI function calling](https://developers.openai.com/api/docs/guides/function-calling).

## Потоковый Android-клиент

Android использует `POST /v1/ai/coach-turn/stream` с прежним JSON-телом, Bearer и
`Accept: text/event-stream`. Старая ручка не используется как fallback.

- `event:text_delta`, data `{requestId, model, delta}` — предварительный фрагмент content.
- `event:completed`, data `{requestId, model, completion}` — единственный авторитетный ответ
  для финального текста и вызовов инструментов.
- `event:error`, data `{requestId, code, message}` — ошибка вместо completed; модель в error
  отсутствует по серверному контракту. `ai_timeout` переводится в существующую ошибку времени,
  `unauthorized` — сессии, прочие — недоступности модели.

Комментарии heartbeat игнорируются. Поддерживаются UTF-8, LF/CRLF/CR и несколько data-строк.
Публичный поток ограничен 4 MiB, событие/envelope — 256 KiB. EOF без completed/error — обрыв.
Владелец, session epoch, requestId и модель проверяются до передачи фрагмента агенту.
Тело POST помечено one-shot. Повторы OkHttp (включая 503 Retry-After: 0), авторизационный replay и redirect отключены.
Отмена закрывает HTTP call и тело ответа. Перед выпуском проверить развёрнутый endpoint:
локальный backend-код подтверждает контракт, но не доступность production.
