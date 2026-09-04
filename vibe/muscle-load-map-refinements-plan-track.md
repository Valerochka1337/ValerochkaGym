# Трекер: уточнения карты нагрузки

| ID | AC | Статус | Доказательство |
|---|---|---|---|
| T-001 | AC-001 | Выполнено | `MuscleSelectorComposeTest`: crossing tick, final tap, silent external sync; точечный Gradle-тест пройден. |
| T-002 | AC-002 | Выполнено | `MuscleSelectorComposeTest`: 1:2:1, непрерывные границы, 48dp и `fontScale = 2.0`; точечный Gradle-тест пройден. |
| T-003 | AC-003 | Выполнено | `MuscleHeatmapProjectionTest`: theme foreground black/white outline, тепловая заливка сохранена; точечный Gradle-тест пройден. |
| T-004 | AC-004 | Выполнено | `versionCode = 20`, `versionName = 1.3.12`; финальные проверки ниже. |

## Финальная проверка

Пройдены: `./gradlew :app:testDebugUnitTest --tests "*MuscleSelectorComposeTest" --tests "*MuscleHeatmapProjectionTest"`.

Пройдены финальные проверки:

- `./gradlew :app:testDebugUnitTest` — успешно.
- `./gradlew :app:assembleDebug` — успешно.
- Независимый review — P0/P1 отсутствуют; исправлены stale-gesture и смещение центра после
  изменения ширины. Остаточных рисков нет.
