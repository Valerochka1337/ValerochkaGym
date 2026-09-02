# Product brief contract

Read this reference when comparing mature options or finalizing an agreed feature.

## Artifact rules

- Write for the owner, designer, and future implementer, not for the conversation transcript.
- Use Russian unless the user requests another language. UI terms may retain established project
  wording.
- Keep the smallest document that preserves the decision. Omit inapplicable sections instead of
  padding them.
- Use stable IDs: `PD-###` for product decisions and `AC-###` for acceptance criteria.
- Label claims as `Факт`, `Вывод`, or `Допущение`. External facts have direct links and research
  dates; repository facts link to files/symbols when useful.
- Describe observable behavior and domain meaning. Leave class, database-table, Compose-component,
  and API design to implementation planning unless an existing contract constrains product
  behavior.
- Never claim user validation that did not occur. Desk research narrows uncertainty; it does not
  replace real behavior or owner acceptance.

## Recommended structure

```markdown
# <Название фичи>

Статус: согласовано / черновик
Обновлено: YYYY-MM-DD

## Решение

- PD-001: <что выбрано>
- Ценность: <какой результат получает пользователь>
- Почему этот вариант: <краткое обоснование>

## Проблема и контекст

- Пользователь и ситуация
- Job-to-be-done / текущая боль
- Желаемый результат
- Что происходит сейчас

## Доказательства и допущения

- Факт: <ссылка или файл, дата>
- Вывод: <как факт влияет на решение>
- Допущение: <что ещё не проверено>

## Рассмотренные варианты

| Вариант | Как работает | Плюсы | Минусы и concerns | Решение |
|---|---|---|---|---|
| … | … | … | … | Выбран / Отклонён / Позже |

## Продуктовое поведение

### Основной сценарий
### Создание и редактирование
### Использование во время тренировки
### История, статистика и поиск
### Пустые, ошибочные и отменённые состояния
### Существующие и устаревшие данные

## Продуктовая семантика данных

- Идентичность и связи сущностей
- Что считается тем же упражнением/вариантом для истории и статистики
- Ожидания по сохранности и обратной совместимости
- Ожидания по экспорту, импорту и синхронизации

## Scope

### Сейчас
### Не входит
### Возможное продолжение

## Acceptance criteria

- AC-001 — Given <контекст>, when <действие>, then <наблюдаемый результат>.

## Проверка ценности и guardrails

- Сигнал успеха
- Способ проверки без неутверждённой телеметрии
- Что не должно ухудшиться

## Риски и компромиссы

| Риск | Вероятность/влияние | Как снизить или проверить | Остаточный риск |
|---|---|---|---|

## Открытые вопросы

- <только вопросы, которые действительно остаются после согласования>

## Журнал решений

| ID | Решение | Почему | Дата |
|---|---|---|---|
| PD-001 | … | … | YYYY-MM-DD |
```

## Behavioral completeness prompts

Use only relevant prompts; they are a review checklist, not mandatory headings:

- Where does the user discover and enter the feature?
- What is the fastest happy path during an active workout?
- Can the user edit, remove, undo, or change their mind without losing history?
- How do duplicates, missing values, invalid combinations, and partially configured legacy records
  behave?
- What does read-only history show after definitions change?
- Does the choice split or merge statistics in a way that surprises the user?
- What happens offline and after sync/import/export round-trips?
- Does the feature create new permissions, sensitive data, disclosure, or accessibility obligations?
- Does a broader model create terminology or configuration burden greater than its future value?

## Acceptance criteria quality

Each criterion describes one observable behavior and can be marked pass/fail by someone who did
not author the brief. Cover happy path, meaningful boundary, failure/recovery, and historical data
where applicable. Avoid implementation wording such as repository names, SQL operations, or exact
Compose widgets unless the user explicitly made them part of the product contract.

For this personal app, do not invent growth metrics or recommend an analytics SDK by default.
Useful validation can include owner task completion, fewer corrective edits, successful round-trip
of existing records, consistent statistics, and explicit qualitative acceptance after real use.
