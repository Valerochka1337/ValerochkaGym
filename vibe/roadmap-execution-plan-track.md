# Выполнение глобального плана

Старт: 10.09.2026. Основание: владелец поручил автономно реализовать все активные доработки,
разрешать детали без вопросов, опубликовать результат в GitHub и проверить успешный релиз.
Объём: [глобальный план](github-issues-implementation-plan.md); социальные #54–62 и лимиты #63
остаются отложенными по отдельным решениям. Статусы подготовки не ограничивают текущую реализацию.

## Git и проверяемость

- База main: cc590a4. Согласованные документы сохранены отдельным коммитом 8ff118e.
- Локальная интеграция: feat/roadmap-delivery. Каждая фича — отдельная ветка и атомарный коммит,
  после её приёмки интеграционная ветка обновляется fast-forward. Ветки/коммиты сохраняются для отката.
- Первый общий выпуск #45/#46: fix/workout-program-save; одна прибавка версии на эту связку.
- Финальная публикация включает проверенные feature-ветки и интеграцию в GitHub, затем контроль
  CI и release asset. Секреты не печатаются. Деплой backend выполняется по найденному контракту.
- Для отката кода — отдельный revert-коммит; выпущенная Android-версия остаётся монотонной.
  Для изменений схем/контрактов планируется совместимая корректирующая миграция, а не понижение
  Room с потерей данных. Каждый этап записывает затронутые контракты и способ восстановления.

## Решения исполнителя по делегации

| ID | Решение | Причина |
|---|---|---|
| D-001 | При нуле выполненных подходов Done выходит без сохранения/перезаписи программы | Не создавать пустую программу и не очищать существующую; история остаётся |
| D-002 | Неизвестные мелкие UX-параметры выбираются по существующим паттернам и фиксируются здесь | Владелец прямо делегировал решения без дополнительных подтверждений |
| D-003 | Предложение профиля — не чаще 72 часов при следующем AI-запросе, с пропуском и отключением; регистрационная карточка в AI-секции | Конкретизация ранее обсуждённой рекомендации, без перехвата тренировки |
| D-004 | Нет истории выбранного упражнения → вес остаётся null, даже если история других упражнений есть | Не переносить нагрузку между несопоставимыми упражнениями |
| D-005 | Программа и дата AI/тренерского предложения подтверждаются одним явным действием после общего предпросмотра; черновик можно отредактировать | Один проверяемый состав и одна идемпотентная операция применения |
| D-006 | Первый локальный рекомендатель — замена недоступного упражнения доступным по оборудованию с близким мышечным профилем | Узкий полезный результат research #11 без зависимости от AI |
| D-007 | В Done-choice явное «Не сохранять» выходит без записи; Back/outside только закрывают диалог. Успешное сохранение из Done-choice выходит с итогов один раз | Не оставлять пользователя в повторяющемся выборе после завершённого действия; отдельное прежнее действие сохранения может оставаться на итогах |
| D-008 | Вкладка остаётся «Календарь»; история группируется по локальной дате начала завершённой тренировки; превью — 5 последних, активная тренировка в историю не входит | Сохранение текущего смысла даты, ограниченный понятный обзор; полный список доступен отдельно |
| D-009 | Базовый профиль: явные nullable поля; занятие 10–240 минут; ограничения до 2000 Unicode codepoints; дата рождения 1900…сегодня; для напоминания достаточно цели и опыта | Добровольные пол и дата рождения не должны вызывать бесконечные напоминания; точная дата не передаётся AI-провайдеру |
| D-010 | Google проверяется при открытии не чаще одного раза в 6 часов на подключённый аккаунт; ручное обновление доступно; ошибки не считаются отсутствием событий | Конкретизация «изредка» без постоянного мониторинга; подробный CAL-02 контракт проверяется до реализации |

## Выпуски

| Этапы / issue | Ветка | Статус | Проверки / commit / версия |
|---|---|---|---|
| 01–02 / #45–46 | fix/workout-program-save | done_local | cdde249; Full 985 tests, 0 failures/errors, 1 skipped; debug PASS; strict review PASS; версия 30 / 1.3.22; Mac full suite через временный forkEvery=16 |
| 03 / #44 | fix/completed-set-edit | done_local | 8c243e4; full1002 tests 0failures,1skipped; debugPASS; GateV/T PASS с принятым popup P2;31/1.3.23 |
| 04 / #47 | fix/workout-finish | done_local | 26b2b96; full1012tests0failures,1skipped/debugPASS; GateT/V PASS;32/1.3.24 |
| 05 / #5 | fix/empty-workout-start | done_local | 6ecb7a0; full1013tests0failures,1skipped/debug/spotlessPASS; independentreviewPASS;33/1.3.25 |
| 06 / #10 | fix/permission-recovery | done_local | 455f05a; full1035tests0failures/1skip,debugPASS,T/VPASS;34/1.3.26 |
| 07 / #48 | feat/yarumo-rebrand | in_progress | Gate P strict PASS; неизменный исходник + XML fill/inset, точные identity/signer/R8/forward rollback gates |
| 08 / #43 | следующая feat/google-account-calendar | plan_ready | Gate P strict PASS; verified connected identity, account-bound operations, ownerless quarantine and saved consent target |
| 11–12 / #51 | следующая feat/guest-sync | plan_ready | Gate P strict recheck PASS, шесть замечаний claim/token/conflict/migration закрыты |
| CAL-01 server slice | backend feat/calendar-plan-contract | done_local | c73c411; full check/bootJar PASS, 44 tests 0 failures; strict PASS; upstream push/deploy blocked READ rights; Android Room slice ждёт этап12 |
| AI-01 server slice | backend feat/server-ai-drafts | done_local | a5b567a; Gate T/V PASS; full check/bootJar64tests0failures;15 Python delivery tests PASS; READ rights block upstream; Android slice ждёт12 |
| 10 / #9 server slice | backend feat/workout-notes-contract | done_local | f26de34; Gate T/V PASS; full68tests0failures/check/bootJarPASS; Android slice waits12/CAL01 |

| 14–15 / #50 server slice | backend feat/basic-profile-contract | done_local | 03a0c1d; Gate T/V PASS; full72tests0failures/check/bootJarPASS; Android follows notes/current schema |

## Инфраструктура и релиз

- Backend найден: `Valerochka1337/ValerochkaGymBackend`, main `dba59ae`; отдельный checkout
  `/private/tmp/yarumo-backend-delivery`. Последний deployment до начала работ прошёл успешно:
  https://github.com/Valerochka1337/ValerochkaGymBackend/actions/runs/34318221584.
- Android main требует PR и статус `Build and test`; approving review не обязателен.
  Публикация final integration пойдёт через PR, а не обход защиты main.
- Backend CI: check/bootJar, admin tests, Docker, immutable digest, backup PostgreSQL,
  deploy и внешний HTTPS health. При ошибке откатывается образ, но не Liquibase.
  Новые серверные контракты сначала принимаются совместимо; обязательный upgrade нельзя
  включать до доступности совместимого Android release.
- Android CI: signed release APK, проверка подписи, unit tests, GitHub Release для новой SemVer.
  Контракт обновлений сохраняет имена `ValerochkaGym-v<version>.apk` и `.sha256`, даже после ребрендинга.
- SMTP production исходно отключён; это существующее инфраструктурное ограничение, Google-вход
  доступен. Значения секретов не читались. Новые AI/provider настройки исследуются отдельно.
- Доступен тестовый `emulator-5554` (arm64); до изменений установлена debug/test-only версия
  27 / 1.3.19. ADB read-only inventory выполнен; данные приложения не очищались.
- Docker 29.5.2 через существующую Colima доступен для backend Testcontainers; контейнеры этой
  проверкой не создавались.
- #45/#46 готовы локально; main/release ещё не опубликованы этой реализацией.
- Финальные результаты CI, release URL, asset и коммиты будут записаны после фактической проверки.

## Текущий внешний блокер

Backend GitHub: viewerPermission READ; SSH dry-run push отказан аккаунту rurkk. Android
viewerPermission WRITE. Actions secrets/variables backend также403. Это ограничение прав GitHub,
не отказ auto-review. Серверный код продолжаем готовить локально; production deploy не объявляем
выполненным. Запрошенных владельцем функциональных изменений из-за этого не исключаем.

Дополнительная read-only проверка через уже подключённый GitHub connector подтверждает backend
permissions: pull=true, push=false, admin=false. Доступ через другой доступный интерфейс также
не даёт прав публикации; попытки записи через него не выполнялись.

Публикация backend: единственный уже подключённый gh аккаунт rurkk, READ; репозиторий публичный.
После завершения кода допустим обычный fork + upstream PR для публикации, без обхода прав.
Это не даёт merge/deploy права upstream и не считается успешным production release.
