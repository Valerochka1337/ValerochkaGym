# Трекер

- T-001 / AC-001: форма сохраняет заявку локально, закрывается по receipt; входы из итогов и календаря. Условие без выбранного зала явно подписано, исключения свёрнуты, обязательного onboarding нет.
- T-002 / AC-002: Room 27, migration 26→27 и историческая 1→27; exact bytes, requestId и ancestry. WorkManager с сетевым constraint, экспоненциальным backoff и девятью попытками; затем явный PAUSED с ручным продолжением того же UUID. APPEND_OR_REPLACE предотвращает поглощение retry завершающимся worker. При старте восстанавливает журнал.
- T-003 / AC-003: backend готов: SQL queue, 90s lease, три recovery исполнения, tombstones, additive endpoints, atomic proposal publication. 198 тестов проверены (2 ожидания числа миграций обновлены и перепроверены отдельно), новый expiry regression также passed. check и bootJar прошли.
- T-004 / AC-004: одна карточка с ожиданием отправки, серверной работой, результатом, ошибкой, изменившимися данными и истёкшей датой. Предыдущий typed результат остаётся доступным.
- T-005 / AC-005: существующий редактор сохраняет ручные правки; offline fallback на кеш подготовленного предложения. Локальный generation fence и серверный current/revision/date fence блокируют устаревшее применение. Старые editable drafts не удаляются, подтверждения уничтожения не требуются.
- T-006 / AC-006: адресные Android проверки пройдены: локальная запись/повторные тапы, lost ACK/recreation, поздний ответ, смена аккаунта, expired, pause/resume, obsolete worker, refresh epoch, READY validation, invalid result, local stale approval. Форма fontScale 2.0 и migration tests passed; итоговый `:app:testDebugUnitTest` PASS (1288 тестов, 0 failures/errors, 1 существующий EmulatorV11CopyMigrationTest пропущен без fixture); `:app:assembleDebug` PASS. APK `app/build/outputs/apk/debug/app-debug.apk`.
- Дополнительный `:app:assembleRelease` остановлен отсутствующей release-подписью; отдельная проверка `:app:minifyReleaseWithR8` PASS без ключей. Подписанный release APK не создавался.

Review: 4 находки исправлены (local stale approval, exhausted retry, auth refresh, server expiry); дополнительная гонка retry/KEEP исправлена APPEND_OR_REPLACE. Снимки, телефон, production AI и deploy не использовались.

Backend: /private/tmp/ValerochkaGymBackend-next-workout, база 0d8f9bc, ветка feat/next-workout-preparation. Android база 093a2d7, та же ветка. Версия 1.3.40 (48), один инкремент над проверенным origin/main.

Проверка владельцем после отдельного обновления backend: завершить тренировку → подготовить следующую; отправить offline и закрыть приложение; после сети проверить READY; изменить условия во время расчёта; открыть/изменить черновик и пересчитать; подтвердить только актуальный вариант. Проверить TalkBack на устройстве. Production restart и реальный провайдер не проверялись, recovery покрыт интеграционными тестами lease/fencing.

Все AC-001…AC-006 закрыты локальной реализацией и перечисленными проверками. Открытых P0/P1 замечаний нет; ручная проверка устройства и production остаётся вне этой задачи. Изменения не закоммичены и не опубликованы.
