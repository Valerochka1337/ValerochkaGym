> Завершение 10.09.2026: исходный разрешённый объём реализован локально на
> `feat/partial-completion`, Room26/version45/1.3.37. Полный unit gate1262/0failures/
> 1conditional skip и debug-сборка PASS. Актуальные доказательства и APK —
> [partial-completion-plan-track.md](partial-completion-plan-track.md), раздел
> «Completion status — current». Ниже сохранён исходный handoff как история.

# Передача завершения начатых функций — 2026-09-10

## Задание новому агенту

Заверши локально только уже частично реализованные функции ValerochkaGym. Нужна реализация до проверенного результата, а не новый план. Новые функции не брать. Работай автономно по существующим планам и контрактам; не переоткрывай согласованные продуктовые решения. Приоритет — минимальное время до завершения без потери данных и пропуска обязательных проверок.

Android: `/Users/raul/ItmoProjects/ValerochkaGym`, ветка `feat/partial-completion`.
Backend: `/Users/raul/ItmoProjects/ValerochkaGymBackend`, ветка `feat/calendar-ai`.
В обоих репозиториях есть намеренные незакоммиченные изменения этой работы. Сначала проверь status/ветку/последние коммиты. Не сбрасывай, не перезаписывай и не теряй WIP. Это продолжение той же задачи в текущих ветках. Не делай push, merge, deploy, публикацию релиза или запросы к живому AI-провайдеру. Не читай секреты. Соблюдай AGENTS.md обоих репозиториев.

Прочитай `.codex/skills/android-feature-implementation/SKILL.md`, `ARCHITECTURE.md`, перед UI — `docs/design-system.md`. Источник объёма: `vibe/partial-completion-plan.md` и `vibe/partial-completion-plan-track.md`. Исторические записи в трекерах могут быть устаревшими: учитывай последние superseding acceptance и фактический код.

## Что уже принято — не реализовывать повторно

- CAL01 локальный календарь: независимые T/V PASS, полный Android gate 1127 тестов / 0 failures / 0 errors / 1 skip; assembleDebug PASS. Room19, версия38/1.3.30 на этом checkpoint. Исправлены порядок родительских/дочерних sync-сущностей и original-instance-date у повторений. Контрольный APK `/private/tmp/yarumo-cal01-v38-debug.apk`. Логи `/private/tmp/yarumo-partial-cal01-full-recheck.log`, `/private/tmp/yarumo-partial-cal01-debug.log`.
- Calendar AI backend: независимые T/V PASS; `check bootJar` PASS, 166 тестов без failures/errors/skips. Лог `/private/tmp/yarumo-partial-calendar-ai-final.log`. Android-клиент ещё не готов.
- Серверные части AI упражнений/InBody, заметок, базового профиля, ручного здоровья, coach-relations и безопасная часть proposals локально приняты по соответствующим трекерам. Не переписывай backend без конкретного выявленного дефекта.

## Первый шаг: принять текущий AI01 после последней серии исправлений

AI01 уже реализован в WIP, но НЕ принят финально. Текущая версия приложения39/1.3.31, Room21. Миграции19→20 и20→21 добавлены в рамках одного AI01; повторно версию за эти исправления не повышать.

Реализация заменяет прямой провайдер/BYOK на backend drafts, удаляет старые настройки/клиенты/logger, добавляет SyncReady, disclosure admission и очистку старых секретов. Сохранять legacy backup/transfer exclusions для ai_secrets.

ВАЖНО: окончательный handoff исполнителя уточнил, что самый последний gate упал на компиляции нового теста `BackendAiRepositoryTest.kt:212`: позиционный конструктор `ExerciseEntity` передаёт String вместо Long и MuscleGroup вместо String, отсутствует type. Первое действие — исправить вызов на именованные аргументы по фактической сигнатуре entity. Предыдущий gate до этого теста был зелёным (41 tasks), но текущее дерево НЕ зелёное. Запись о PASS в tracker относится к предыдущему состоянию.

После исправления: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests '*BackendAiRepositoryTest' --tests '*HealthAiDisclosureRepositoryTest' --tests '*BackendSyncTest' --tests '*Migration19To20Test' --tests '*Migration20To21Test' --tests '*Migration1To21Test' --tests '*MeasurementEditorViewModelTest' :app:compileDebugKotlin spotlessCheck`. Затем независимый review и финальные gates.

Исполнитель подтвердил остановку: больше не изменяет файлы и не запускает Gradle. Старый главный агент завершил работу подготовкой этого handoff; владение всем WIP передаётся новому агенту.

Предыдущий review нашёл пять P1. Исполнитель внёс исправления; независимо перепроверь именно их:
1. Offline grant→revoke: сначала дослать неизменяемый старый запрос, затем обязательно актуальное false; немедленное запрещение загрузки через DataStore. Ответ сервера не должен перезаписать актуальное пользовательское намерение.
2. Durable intent/outbox восстанавливаются после смерти процесса; Room21 intent journal, миграция backfill, атомарная замена при stale CAS, literal-byte replay.
3. Capabilities, raw receipt bytes и owner относятся к конкретному `BackendResponse`, а не глобальному mutable состоянию транспорта.
4. Оба AI POST используют `authorizedResponse(... retryOnUnauthorized=false)`; 401 не повторяет генерацию или фото. Проверить реальным transport-тестом.
5. Поздний ответ A не применяется к B и не связывает remote UUID с чужим local Long; проверить owner/cache смену вокруг suspend-маппинга и открытия редактора. Текущий `BackendAiRepository.isCurrent` сравнивает повторный `syncReady.await()` с Ready — отдельно проверить достаточность при изменениях локального cache и A→B→A. Это риск для review, не утверждение о подтверждённом дефекте.

Также проверь строгие exercise DTO/requestId/revision, readiness denial (guest/active/outbox/conflict/catalog), consent UI semantics/font-scale2. No photo encode/upload без enabled same-owner receipt текущего notice и точного `X-Health-AI-Disclosure-Revision`; health-ledger-v1 capability должна объединяться с существующими capabilities. Поздний revoke/owner switch отбрасывает ответ.

Основные файлы: `data/ai/BackendAiRepository.kt`, `data/backend/BackendApi.kt`, `BackendSync.kt`, `SyncReadyAdapter.kt`, `data/health/HealthAiDisclosureRepository.kt`, consent DAO/entities, GymApplication, MeasurementEditorViewModel, ExerciseLibraryViewModel и их тесты. Пути относительно `app/src/main/java/com/valerochka1337/valerochkagym/`.

## Остаток после AI01

1. Заметки тренировок/подходов и персональные подсказки: `vibe/workout-notes-plan.md`, tracker и `workout-notes-sync-contract.json`. Исследование швов уже записано в tracker. Notes-only подход не терять при finish/prune; копирование routine сохраняет completed-only семантику. Подсказки к STANDARD упражнению редактируются независимо от запрета менять каталог. Capability-aware sync учитывает текущие данные и ACK baseline, сохраняет точные outbox bytes.
2. Базовый профиль: `vibe/basic-profile-plan.md`, tracker и sync contract. Реализовать Android целиком, включая owner isolation, sync и запланированные UI/gate сценарии.
3. Ручное здоровье: `vibe/manual-health-plan.md`, tracker, `vibe/contracts/manual-health-contract.json`. Переиспользовать уже созданные AI01 consent state/outbox/intent/DAO; не создавать второе хранилище согласия. Следующую Room-миграцию строить от фактического текущего номера, сейчас21.
4. Coach relations: `vibe/coach-relations-plan.md` и tracker; план прошёл независимый Gate P. Базовый relation/grants/read-client можно готовить независимо. Authoring зависит от proposals. Не добавлять новые виды доступа. Invite token transient-only, не сохранять raw token в journal/SavedState/log. Canonical backend fixture SHA-256 `f5960d8a8fd269518aa6347b18607a778e4eff7f64aa5ecd9a2dc529aa501460`.
5. Training proposals: `vibe/training-proposals-plan.md`, tracker, contract. Часть поведения заблокирована решением ниже; не останавливай из-за неё независимые части.
6. Calendar AI Android: `vibe/calendar-ai-brief.md` и агрегированный план. Уже начатая функция, в scope. Завершать после notes/profile/proposals, используя принятый backend. Не добавлять новый AI-продукт.

Вне scope: Google CAL02/Google-синхронизация, новый месячный UI, рекомендации, питание, расширенные метрики, импорты и новый social backlog. Оставшиеся проверки входят в scope только для перечисленных функций.

## Единственная известная блокировка решения

Ранее автоматическая проверка разрешений отклонила снятие проверки равенства отредактированного preview исходному плану. Явное разрешение на эту правку НЕ получено. Не считай просьбу ускорить/передачу задачи таким разрешением. Не применяй `/private/tmp/yarumo-proposal-edited-approval-proposed.patch` и не обходи запрет эквивалентным изменением. Если без решения нельзя закрыть PLAN01, задай один конкретный вопрос о разрешении подтверждать изменённый пользователем draft после валидации, объяснив прежний отказ automatic approval. Продолжай независимую реализацию. Не объявляй весь scope завершённым при оставшейся блокировке.

## Как ускорять

- Используй специализированных субагентов согласно skill: независимый read-only review AI01 параллельно подготовке следующих slices; исполнителям задавай непересекающееся владение файлами. Все знают о совместном дереве и не откатывают чужие правки.
- Назначь ОДНОГО владельца Room/миграций/schema/общего SyncSchema/PortableData/BackendSync и интеграции. UI/repositories/test preparation разных slices можно разделять, предварительно договорившись о контрактах. Не запускай несколько Gradle одновременно.
- Переиспользуй существующие briefs/планы/контракты/исследование, не устраивай новый discovery. Не перечитывай весь проект каждым агентом.
- Во время исправлений запускай адресные тесты. На принятой границе фичи выполняй обязательные full unit + debug gates, unsigned release где это требует план. Не повторяй зелёные gates без нового изменения/риска; не пропускай их ради скорости.
- JDK21. На этом Mac полный Robolectric прогон надёжно выполнялся с `/private/tmp/yarumo-test-isolation.gradle` (`forkEvery=1`, `maxParallelForks=1`, без исключения тестов). Команда: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew --no-daemon :app:testDebugUnitTest --init-script /private/tmp/yarumo-test-isolation.gradle --console=plain`, затем assembleDebug. Проверить наличие init-файла. Для Gradle может потребоваться sandbox escalation из-за кэша.
- Backend при необходимости: JDK21, `DOCKER_HOST=unix:///Users/raul/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew --no-daemon check bootJar`. Не повторять принятый backend gate без изменений.
- Не создавать/не открывать screenshots и видео без явного запроса. UI проверять semantics/accessibility. Не добавлять mock-библиотеки, destructive Room fallback, новые UI-библиотеки.
- Каждая завершённая фича получает ровно один versionCode/patch increment; следи за актуальной версией, не считай миграцию отдельной фичей.

Продолжай до завершения всего разрешённого остатка. Обновляй агрегированный tracker фактическими результатами и кратко сообщай прогресс. В финале отдельно перечисли принятые функции, результаты проверок и реальные блокировки. Локальная готовность не равна опубликованному релизу.
