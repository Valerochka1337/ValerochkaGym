# CLAUDE.md

Персональное Android-приложение для силовых тренировок. Один модуль `:app`.
Обзор слоёв и решений — [ARCHITECTURE.md](ARCHITECTURE.md); правила UI —
[docs/design-system.md](docs/design-system.md) (источник правды, читать перед любой UI-работой).

## Команды

```bash
./gradlew :app:testDebugUnitTest      # все unit-тесты (JUnit4 + Robolectric)
./gradlew :app:assembleDebug          # debug-сборка
./gradlew :app:assembleRelease        # release: R8 + сжатие ресурсов (без подписи)
./gradlew :app:testDebugUnitTest --tests "*ИмяТеста"   # один класс
```

## Стек и версии

AGP 9.x со встроенным Kotlin — плагин `kotlin.android` НЕ применять. Material3 закреплён
alpha-версией поверх Compose BOM ради Expressive API (`MaterialExpressiveTheme`,
`motionScheme`). Hilt + KSP (без kapt). Версии — только через `gradle/libs.versions.toml`.

## Правила кода

- **Строки UI хардкодятся в Kotlin** (решение владельца): приложение одноязычное,
  `strings.xml` — только `app_name` и OAuth client ID. Не выносить тексты в ресурсы.
- **Room — только рукописные миграции** (схемы в `app/schemas/`), destructive fallback запрещён.
- **Моушн — только токены `GymMotion`** (`ui/theme/Motion.kt`); инлайновые `spring`/`tween`
  в экранах запрещены. Сторонние анимационные библиотеки и библиотеки графиков не добавлять.
- **Хаптика — только семантика `GymHaptics`** (`gymHaptics()`), не сырой `performHapticFeedback`.
- **Цвета — только `MaterialTheme.colorScheme.*`**; хардкод `Color(0x…)` вне `ui/theme/` запрещён
  (исключение — шкалы данных `ChartPalette`, см. design-system §3).
- Логирования в проекте нет намеренно — не добавлять `Log.*`; ошибки показываются в UI.
- Тяжёлые вычисления в потоках — через `flowOn(@ComputeDispatcher)`, чтобы тесты могли
  подменить диспетчер.

## Тесты

- JUnit4, имена — backtick-предложения в настоящем времени:
  `` fun `completing a set marks it done and starts rest`() ``.
- **Рукописные фейки, приватные в файле теста** — мок-библиотек в проекте нет и не будет.
- ViewModel-тесты: `MainDispatcherRule` + `runTest(mainDispatcherRule.testDispatcher.scheduler)`;
  `stateIn(WhileSubscribed)` холодный — перед чтением `uiState.value` цеплять живой
  коллектор (хелпер `collectUiState`). Образец: `ui/WorkoutsViewModelTest.kt`.
- Robolectric — только где неизбежен Android (DAO — база `RoomDaoTest`, воркеры, рендер);
  закреплён `sdk=36` (`app/src/test/resources/robolectric.properties`), тестовый JVM — JDK 21.
- Тесты приватных методов не писать.
- Проверка стадии работы: полный `testDebugUnitTest` + `assembleDebug`; после правок
  графиков смотреть снимки `AnalysisRenderTest` в `app/build/reports/analysis-render/`.

## Git

Сообщения коммитов — на русском, со смысловым префиксом (`feat:`, `test:`, `refactor:`,
`perf:`, `build:`, `chore:`, `docs:`). Планы работ и трекеры — в `vibe/`
(`<имя>-plan.md` + `<имя>-plan-track.md`).
