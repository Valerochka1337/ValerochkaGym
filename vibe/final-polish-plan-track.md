# Progress tracker for final-polish-plan.md

Формат статуса: [ ] — не выполнено, [X] — выполнено

## Стадии выполнения

[X] Стадия 1: Git-подготовка (2026-08-02: merge ff в main, ветка feature/final-polish, план+трекер, базовая линия тестов зелёная)
[X] Стадия 2: Тесты чистой доменной логики (MuscleLandmarks, CardioMet, MuscleDefaults)
[X] Стадия 3: Тесты форматтеров
[X] Стадия 4: Тесты ActiveWorkoutViewModel (11 тестов: реальные mutator/use case/таймер над фейками — тот же single-writer путь, что у уведомления)
[X] Стадия 5: Тесты остальных ViewModel (13 тестов; сортировка дерева WorkoutSummary зафиксирована для дедупа в ст.8)
[X] Стадия 6: Тесты воркера, планировщика и AppIconManager (+work-testing; всего в проекте 422 теста, покрытие зафиксировано)
[X] Стадия 7: Производительность (flowOn через инжектируемый @ComputeDispatcher — тесты остались синхронными; кэш ParsedBody; ключи списков через новые id в UI-моделях; один combine-коллектор уведомления; рингтон на IO)
[X] Стадия 8: Рефакторинг — мёртвый код, дубли, устаревшие комментарии (+ParsedRows.skippedRows; замечена разовая утечка исключения между тестами в общем JVM — не воспроизвелась за 3 прогона)
[X] Стадия 9: Моушн-токены и миграция существующих анимаций (ui/theme/Motion.kt: GymMotion; все прежние сайты на токенах)
[X] Стадия 10: Анимация графиков и календаря (reveal столбцов/линии, пружинный маркер ZoneMeter, направленный AnimatedContent месяца; AnalysisRenderTest зелёный, снимки проверены глазами)
[X] Стадия 11: Микропереходы (GymFilterChip с анимированным выделением; анимированная рамка карточки рутины; animateItem во всех списках; AnimatedContent в UploadStatusBadge; FadeInContent после загрузки)
[X] Стадия 12: Хаптика — GymHaptics + настройка haptics_enabled (семантическая обёртка, LocalGymHaptics из MainActivity; сайты: подход/степперы/рест-пилюля/финиш/табы/рутины/карта тела/PR)
[ ] Стадия 13: Автостарт таймера отдыха
[ ] Стадия 14: Экран настроек — группа отдыха, виброотклик, секция «Данные»
[ ] Стадия 15: Release-конфигурация
[ ] Стадия 16: Документация, CHANGELOG и финальная проверка

## Заметки

- Стадия 1: удаление устаревших веток (feat/analysis-tab, feat/calendar, feat/calendar-tab,
  feat/redesign-material3-single-accent, feat/ui-analysis-tab-settings-circle,
  feature/body-heatmap-highlighter) и worktree feat-calendar отложено — авто-режим запрещает
  удаление веток без явного подтверждения пользователя. Ветки безвредны, main актуален.
