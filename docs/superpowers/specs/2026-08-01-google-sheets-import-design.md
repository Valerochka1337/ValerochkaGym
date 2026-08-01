# Импорт истории тренировок из Google Таблицы

Дата: 2026-08-01

## Задача

Сейчас интеграция с Google Sheets работает только на экспорт: завершённые
тренировки выгружаются в лист `Workouts` целевой таблицы (`SheetsRepository` →
`WorkoutRowMapper` → append строк «один подход = одна строка»). Обратного пути нет.

Нужно, чтобы **при добавлении ссылки на таблицу в настройках** приложение разово
подтянуло историю тренировок из этого же листа `Workouts` в локальную БД (Room):
восстановило тренировки, упражнения и подходы. Основной сценарий — переезд на новый
телефон: старое устройство выгружало историю в таблицу, новое должно её импортировать.

## Границы (scope)

- **Источник** — только родной формат экспорта приложения (лист `Workouts`, 14 колонок
  `A–N`, см. `WorkoutRowMapper.HEADER_ROW`). Произвольные пользовательские таблицы —
  вне рамок.
- **Триггер** — только при сохранении ссылки в настройках. Кнопки «синхронизировать»
  и фонового авто-синка нет.
- **Направление** — только импорт (Sheets → БД). Двусторонняя синхронизация вне рамок.

## Известные компромиссы (потери экспорта)

Экспорт пишет не все поля, поэтому при импорте они восстанавливаются приближённо:

| Поле                     | При импорте                                             |
|--------------------------|--------------------------------------------------------|
| `finishedAt`             | = `startedAt` (в таблице нет; нужно ненулевым, иначе тренировка не попадёт в историю — `observeFinishedWorkouts` фильтрует `finishedAt IS NOT NULL`) |
| `note`                   | пустая строка                                          |
| `routineId`              | `null`                                                 |
| секунды времени старта   | теряются (в таблице только `HH:mm`)                    |
| `uploadStatus`           | `UPLOADED` — импортированное уже есть в таблице, повторно выгружать не нужно |
| `volume`                 | игнорируется (производное поле)                         |

## Архитектурное решение

Зеркалим существующий экспорт. Поток при сохранении ссылки:

1. `SettingsViewModel.setSpreadsheetInput` сохраняет `spreadsheetId`, затем зовёт
   `ImportWorkoutsUseCase`.
2. UseCase берёт токен (`GoogleAuth.getAccessToken`), читает весь лист
   `Workouts!A:N` через `SheetsApi.getValues`, отдаёт строки парсеру.
3. `WorkoutRowParser` (новый `object`, обратный к `WorkoutRowMapper`) превращает
   плоские строки в дерево `ParsedWorkout` → `ParsedExercise` → `ParsedSet`.
4. `WorkoutImportRepository` в транзакции вставляет только новые тренировки,
   матчит/создаёт упражнения, возвращает число импортированных.
5. ViewModel показывает результат снэкбаром через существующий канал `messages`.

Операция синхронная в корутине ViewModel (разовая, на переднем плане). WorkManager
не используется.

### Разбор строк (`WorkoutRowParser`)

- Первую строку, равную `WorkoutRowMapper.HEADER_ROW`, пропускаем. Пустые строки и
  строки без `workout_id` (колонка A) пропускаем.
- Группировка по `workout_id` (порядок появления сохраняем).
- Внутри тренировки: `startedAt` — из `date` (`yyyy-MM-dd`) + `start_time` (`HH:mm`)
  в системной таймзоне → epoch millis. `workout_name` — из колонки D.
- Упражнения группируются по имени (колонка E) в порядке первого появления →
  `position` (0,1,2…). Группа и тип берутся из RU-названий (колонки F, G) через
  обратный маппинг.
- Подходы: `setIndex = set_index − 1`, числовые поля парсятся из строк
  (`weight_kg`, `reps`, `duration_sec`, `speed_kmh`, `incline_pct`); пустая ячейка →
  `null`. `isCompleted = true` (экспорт пишет только выполненные).
- Некорректные числовые ячейки → `null` (не роняем импорт).

### Обратный маппинг enum (`EnumDisplay.kt`)

Добавить `muscleGroupFrom(label: String): MuscleGroup` и
`exerciseTypeFrom(label: String): ExerciseType` — обратные к `displayName()`.
Неизвестная метка → фоллбэк (`MuscleGroup.FULL_BODY`, `ExerciseType.STRENGTH`),
чтобы импорт не падал на нестандартных значениях.

### Вставка (`WorkoutImportRepository`)

Для каждой `ParsedWorkout`:

- Если `workout_id` уже есть локально (`WorkoutDao.getExistingWorkoutIds`) — пропуск.
- Иначе создаём `WorkoutEntity(id=workout_id, name, startedAt, finishedAt=startedAt,
  uploadStatus=UPLOADED)`.
- Для каждого упражнения: матч по имени (case-insensitive) через
  `ExerciseDao.findByName`; если нет — `ExerciseDao.insert(ExerciseEntity(name,
  muscleGroup, type, isCustom=true))`. Матчинг именно по имени (по решению — без учёта
  группы/типа).
- Вставляем `WorkoutExerciseEntity` (с `position`) и его `WorkoutSetEntity`.

Вся вставка одной тренировки — под `@Transaction`.

### Обработка ошибок

Симметрично экспорту (`SheetsRepositoryImpl.classifyHttp` / `GoogleErrorMessages`):

- нет `spreadsheetId` / токена → сообщение «Настройте доступ к Google».
- `IOException` → «Нет сети».
- HTTP 401/403 → «Нет доступа к таблице»; 404 → «Таблица не найдена».
- Лист `Workouts` отсутствует или пуст → «Нечего импортировать».

Все сообщения — коротким снэкбаром. Импорт best-effort: одна разовая попытка.

## Файлы к созданию/изменению

- `data/google/WorkoutImportRepository.kt` — **новый**. Интерфейс + Impl: чтение листа,
  вызов парсера, транзакционная вставка, классификация ошибок; возвращает
  sealed-результат (`ImportResult.Success(count)` / `Failure(reason)` / `NothingToImport`).
- `domain/ImportWorkoutsUseCase.kt` — **новый**. Тонкая оркестрация: токен → репозиторий.
- `domain/WorkoutRowParser.kt` — **новый**. Обратный к `WorkoutRowMapper`, с DTO
  `ParsedWorkout`/`ParsedExercise`/`ParsedSet`.
- `domain/EnumDisplay.kt` — добавить `muscleGroupFrom`, `exerciseTypeFrom`.
- `data/db/dao/WorkoutDao.kt` — добавить `getExistingWorkoutIds(): List<String>` и
  транзакционный метод вставки дерева импортированной тренировки (или отдельный
  `@Dao` `WorkoutImportDao`).
- `data/db/dao/ExerciseDao.kt` — добавить `findByName(name: String): ExerciseEntity?`
  (`COLLATE NOCASE`).
- `ui/settings/SettingsViewModel.kt` — после `setSpreadsheetId` вызвать
  `ImportWorkoutsUseCase` и отправить результат в `messages`.
- `di/GoogleModule.kt` — привязка `WorkoutImportRepository`.

## Тесты

По образцу существующих (`WorkoutRowMapperTest`, `SheetsRepositoryTest`, `*DaoTest`):

- `WorkoutRowParserTest` — round-trip с `WorkoutRowMapper.rows` (экспорт→импорт даёт
  эквивалентное дерево), пропуск шапки/пустых строк, парсинг числовых полей и `null`,
  фоллбэк неизвестных RU-меток, группировка по `workout_id` и по упражнению с
  корректным `position`.
- `WorkoutImportRepositoryTest` — дедуп по существующему `workout_id`, матч упражнения
  по имени vs создание нового, простановка `finishedAt=startedAt` и
  `uploadStatus=UPLOADED`, классификация ошибок.
- DAO-тесты (`WorkoutDaoTest`, `ExerciseDaoTest`) — `getExistingWorkoutIds`,
  `findByName`, транзакционная вставка дерева.

## Acceptance criteria (DoD)

1. При сохранении корректной ссылки история из листа `Workouts` появляется в разделе
   истории приложения; повторное сохранение той же ссылки не создаёт дублей.
2. Импортированные тренировки имеют статус `UPLOADED` и не выгружаются экспортом обратно.
3. Упражнения из таблицы матчатся по имени с локальным справочником; отсутствующие
   создаются как `isCustom=true`.
4. Ошибки (нет доступа/сети/таблицы, пустой лист) показываются понятным снэкбаром,
   приложение не падает.
5. Написаны и проходят тесты парсера, репозитория импорта и DAO.
