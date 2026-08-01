# Прогресс-трекер для valerochka-gym-plan.md

Формат статуса: [ ] - не выполнено, [X] - выполнено

## Стадии выполнения

[X] Стадия 1: Перевод проекта на Kotlin/Compose (Gradle-каркас)
[X] Стадия 2: Дизайн-система (тема «C»)
[X] Стадия 3: Навигация и каркас экранов
[X] Стадия 4: Room, DataStore, сидирование упражнений
[X] Стадия 5: Тесты слоя данных
[X] Стадия 6: Библиотека упражнений (UI)
[X] Стадия 7: Тесты библиотеки
[X] Стадия 8: Программы (список + редактор)
[X] Стадия 9: Тесты программ
[X] Стадия 10: Домен активной тренировки
[X] Стадия 11: Тесты домена
[X] Стадия 12: UI активной тренировки (вариант B)
[ ] Стадия 13: Foreground service и таймер отдыха
[ ] Стадия 14: Тесты таймера
[ ] Стадия 15: История
[ ] Стадия 16: Вход Google и настройки
[ ] Стадия 17: Маппинг тренировки в строки Sheets
[ ] Стадия 18: Тесты маппера
[ ] Стадия 19: Выгрузка в Sheets (WorkManager)
[ ] Стадия 20: Тесты выгрузки
[ ] Стадия 21: Google Calendar (планирование)
[ ] Стадия 22: Тесты календаря
[ ] Стадия 23: Полировка и финальная проверка

## Заметки

*   2026-07-31: Robolectric 4.16 + SDK 36 требует JDK 21 для запуска тестов (android-jar скомпилирован Java 21), при jvmToolchain(17) у приложения. На Стадии 5 настроить JVM 21 для тестового таска (или запуск Gradle на JDK 21).
*   Версии после Стадии 1: okhttp 5.4.0, androidx.test core-ktx 1.7.0, lifecycle-viewmodel-compose 2.9.4.

*   Стадия 2: material-icons-core допустим, но НЕ добавлять material-icons-extended — иконки вкладок и далее делать локальными vector drawable (Material Symbols); когда PlayArrow останется единственным потребителем, material-icons-core убрать.
*   Стадия 2: secondaryContainer намеренно teal-derived (пилюля NavigationBar), а не peach.
*   Стадия 4 (решения): ExerciseDao.search удалён — поиск в Стадии 6 делать в памяти (Kotlin contains(ignoreCase=true), кириллица); сидирование идемпотентно (onOpen + count()==0); lastCompletedSetsForExercise — семантика с EXISTS-фолбэком на последнюю тренировку с выполненными подходами (пиновать тестами Стадии 5); plannedSets — типизированное поле List<PlannedSet> (колонка plannedSetsJson).
*   Стадия 5: тестовая инфраструктура — JVM тестов = JDK 21 (Robolectric 4.16, sdk=36 в robolectric.properties), abstract RoomDaoTest (lifecycle in-memory БД + tableCount) — ОБЯЗАТЕЛЬНЫЙ шаблон для тестовых стадий 7/9/11/14/18/20/22; @Config(application=Application) для обхода Hilt.
*   Стадия 6 (шаблон UI): nullable exercises в UiState (null=loading, без вспышки пустого состояния), вычисляемый isEmpty, collectAsStateWithLifecycle (lifecycle-runtime-compose добавлен, общий version ref `lifecycle`), rememberSaveable для состояния диалогов — ЭТО ШАБЛОН для экранов стадий 8/12/15/16/21.
*   На Стадию 10: мигрировать ViewModel'и стадий 6/8 с прямых DAO на репозитории. Отложено: per-row лямбды, TextFieldState.
*   Стадия 7 (шаблон VM-тестов): MainDispatcherRule с testDispatcher — в runTest передавать mainDispatcherRule.testDispatcher.scheduler (один виртуальный клок!); фейки DAO — приватные в файле теста (1:1 с DAO, не выносить); live-коллектор для WhileSubscribed.
*   Стадия 8: отложено сознательно — уточнение формулы длительности (durationSec для TIMED/CARDIO), субминутная гранулярность кардио, стакинг «(копия)», rememberSaveable в NumberField. Несохранённые правки редактора теряются при смерти процесса — допустимо для v1 (бэклог).
*   Стадия 9: решение — миграцию VM стадий 6/8 на репозитории НЕ делаем (YAGNI: прямые DAO — принятый паттерн, репозитории только там, где есть логика — ActiveWorkoutRepository в Стадии 10). Отложено: KDoc-парность collectUiState, bounds-guard тесты updatePlannedSet.
*   Стадия 10: ОБЯЗАТЕЛЬНО для Стадии 12 — single-flight/debounce кнопок старта во ViewModel (check-then-insert guard не закрывает гонку двойного тапа полностью). startFromRoutine/startEmpty/finish — в withTransaction; finish идемпотентен. Отложено: Clock/id-семы, rename/note-операции, субминутное кардио-форматирование.
*   Стадия 11: сид-хелперы insertWorkout/insertWorkoutExercise/insertSet/workoutFull — в RoomDaoTest (использовать в будущих DB-тестах). 98 тестов.
