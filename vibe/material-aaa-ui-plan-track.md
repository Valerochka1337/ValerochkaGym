# Progress tracker for material-aaa-ui-plan.md

Формат статуса: `[ ]` — не выполнено, `[~]` — выполняется, `[X]` — выполнено.

## Статус согласования

* [X] UI-аудит кода, структуры экранов, темы, Canvas и render-тестов выполнен (2026-09-01).
* [X] Dark-only и отсутствие dynamic color зафиксированы как дефекты по решению владельца.
* [X] План реализации одобрен владельцем явным «Ок» (2026-09-01).
* [X] Реализация начата (2026-09-01).

## Стадии реализации

* [X] Стадия 0: контракт design system и тестовый каркас.
* [X] Стадия 1: theme engine, светлая/системная тема и Material You dynamic color.
* [X] Стадия 2: adaptive app shell, navigation bar/rail и общие Material app bars.
* [X] Стадия 3: Material-компоненты, 48 dp targets и accessibility foundation.
* [X] Стадия 4: Workouts и Active Workout — явный старт и фокус на текущем подходе.
* [X] Стадия 5: Analysis — «Обзор / Нагрузка / Прогресс».
* [X] Стадия 6: Measurements и progressive-disclosure editor.
* [X] Стадия 7: Calendar, Exercises, detail screens, Settings IA и permissions UX.
* [X] Стадия 8: консистентность, документация и полный quality gate.

## Quality gates

* [X] Интерактивные primitives и проверенные compound rows имеют bounds не меньше 48×48 dp.
* [X] Canvas-графики и карта мышц имеют semantic custom actions; стандартные действия используют
  Material semantics.
* [X] 200% fontScale закреплён component-тестом; длинные routes/sheets получили scroll и sticky actions.
* [X] Compact использует navigation bar, expanded — rail; поведение закреплено тестами 360/1200 dp.
* [X] System/Light/Dark и System/custom palette сохраняются и переключаются корректно.
* [X] Фирменные color schemes проходят зафиксированные contrast-тесты 7:1.
* [X] Необъяснимые пустые loading-ветки routes заменены явным progress state.
* [X] `./gradlew :app:testDebugUnitTest` проходит.
* [X] `./gradlew :app:lintDebug` проходит без baseline и BLE suppressions.
* [X] `./gradlew :app:assembleDebug` проходит.
* [X] Снимки аналитики, карт тела, детали упражнения и измерений проверены визуально.

## Реализовано 2026-09-01

* `GymTheme` поддерживает системный/светлый/тёмный режим, dynamic color и четыре полные
  фирменные light/dark схемы; настройки сохраняются в DataStore.
* Оболочка переведена на `NavigationSuiteScaffold`, контент ограничен 960 dp, стандартные
  filled/outlined состояния навигации проверены compact/expanded тестами.
* Общие кнопки, карточки и app bar переведены на M3 primitives; календарь, графики и карта
  мышц получили полноценную семантику и доступные действия.
* Workouts, Active Workout, Analysis, Measurements, редактор измерений и Settings получили
  новую иерархию и progressive disclosure; permission flows объясняют запрос и допускают отказ.
* Устранены 6 старых BLE lint errors корректными runtime permission checks и
  `BluetoothStatusCodes.SUCCESS`; `lintDebug` полностью зелёный.
* По результатам проверки на устройстве возвращён выбор программы тапом и закреплённый блок
  старта/пустой тренировки; явные кнопки старта внутри каждой карточки удалены по решению владельца.
* Карточки редактора программы сворачивают подходы, показывают число подходов/отдых и стрелку;
  delete предшествует grip, поэтому перетаскивание всегда находится у правого края.

## Ручная приёмка на устройстве

Автоматические проверки закрыты. Перед релизом остаётся пройти аппаратно-зависимую матрицу:
TalkBack/Switch Access, физическая клавиатура, IME, gesture navigation, split-screen/fold posture
и три реальные wallpaper dynamic palette. Это проверка платформенной интеграции, а не оставшиеся
изменения кода.

## Базовая линия до реализации

* Unit-тесты: 617 passed, 0 failed, 0 skipped.
* Debug build: успешно.
* Lint: 6 ошибок BLE и 23 предупреждения; UI warning в `ExerciseAvatar.kt`.
* Код приложения в рамках ревью не изменялся.

## Заметки по решениям

* Строки UI остаются в Kotlin согласно `AGENTS.md`.
* `GymMotion`, `GymHaptics`, `MaterialTheme.colorScheme` и `ChartPalette` сохраняются как
  обязательные системные границы.
* Серьёзная переработка информационной архитектуры разрешена, но начинается только после
  явного «Ок» владельца.
