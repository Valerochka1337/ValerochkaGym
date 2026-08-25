# Progress tracker for sheets-recovery-plan.md

Status format: [ ] - not processed, [X] - completed

## Execution Stages

[X] Stage 1: Зафиксировать контракт синхронизации
[X] Stage 2: Добавить сериализацию и импорт данных Sheets
[X] Stage 3: Выгружать программы надёжно
[X] Stage 4: Связать восстановление с настройками и покрыть тестами

## Notes

- 2026-08-25: План и трекер созданы. Контракт выбран append-only: новая версия программы
  дописывается в `Routines`, импорт берёт строку с максимальной версией; удаление — tombstone.
- 2026-08-25: Реализованы импорт замеров и программ, `Routines` с версиями/tombstone, очередь
  WorkManager и запуск восстановления после Google-входа при восстановленном ID таблицы.
  Проверки: `:app:testDebugUnitTest` (584 теста) и `:app:assembleDebug`.
