# Импорт истории из Google Таблицы — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** При сохранении ссылки на Google Таблицу разово импортировать историю тренировок (лист `Workouts`) в локальную БД; попутно писать честное время каждого подхода (`completedAt`), чтобы экспорт не ставил всем строкам одинаковый `start_time`.

**Architecture:** Зеркалим существующий экспорт. Новое поле `WorkoutSetEntity.completedAt` заполняется при отметке подхода и используется экспортом (`WorkoutRowMapper`). Обратный парсер (`WorkoutRowParser`) превращает строки листа в дерево, `WorkoutImportRepository` вставляет только новые тренировки (дедуп по `workout_id`), матчит упражнения по имени. Импорт запускает `SettingsViewModel` после сохранения ссылки. Флоу токена/настроек/чтения/вставки владеет сам репозиторий (как `SheetsRepositoryImpl` во владении `uploadWorkout`) — отдельный use case из спеки не заводим (YAGNI).

**Tech Stack:** Kotlin, Room (миграция 1→2), Retrofit + kotlinx.serialization (Sheets API v4), Hilt, Coroutines. Тесты — JUnit4 + Robolectric + room-testing, по образцу существующих (`RoomDaoTest`, `SheetsRepositoryTest`, `WorkoutRowMapperTest`, `SettingsViewModelTest`).

---

## Файловая структура

**Изменяемые (честное время подхода):**
- `app/src/main/java/.../data/db/entity/WorkoutSetEntity.kt` — поле `completedAt: Long?`.
- `app/src/main/java/.../data/db/GymDatabase.kt` — `version = 2`, `MIGRATION_1_2`.
- `app/src/main/java/.../di/DataModule.kt` — регистрация миграции.
- `app/src/main/java/.../data/db/dao/WorkoutDao.kt` — сигнатура `setSetCompleted`, метод `getExistingWorkoutIds`.
- `app/src/main/java/.../data/db/dao/ExerciseDao.kt` — метод `findByName`.
- `app/src/main/java/.../data/ActiveWorkoutRepositoryImpl.kt` — прокидывание `completedAt`.
- `app/src/main/java/.../domain/WorkoutRowMapper.kt` — экспорт даты/времени из `completedAt`.
- `app/src/main/java/.../domain/EnumDisplay.kt` — обратные `muscleGroupFrom`/`exerciseTypeFrom`.
- `app/src/main/java/.../ui/settings/SettingsViewModel.kt` — запуск импорта.
- `app/src/main/java/.../di/GoogleModule.kt` — биндинг `WorkoutImportRepository`.

**Создаваемые:**
- `app/src/main/java/.../domain/WorkoutRowParser.kt` — парсер + DTO `Parsed*`.
- `app/src/main/java/.../data/google/WorkoutImportRepository.kt` — интерфейс + Impl + `ImportResult`.
- `app/src/test/java/.../data/db/Migration1To2Test.kt`
- `app/src/test/java/.../domain/EnumDisplayTest.kt`
- `app/src/test/java/.../domain/WorkoutRowParserTest.kt`
- `app/src/test/java/.../data/WorkoutImportRepositoryTest.kt`

**Изменяемые тесты:**
- `app/src/test/java/.../domain/WorkoutRowMapperTest.kt` — `completedAt` в хелпере + новые кейсы.
- `app/src/test/java/.../data/WorkoutDaoTest.kt` — `getExistingWorkoutIds`, запись `completedAt`.
- `app/src/test/java/.../data/ExerciseDaoTest.kt` — `findByName` (файл существует; если нет — создать по образцу).
- `app/src/test/java/.../ui/SettingsViewModelTest.kt` — импорт после сохранения ссылки.

Пакет во всех путях: `com.valerochka1337.valerochkagym`.

---

### Task 1: Миграция БД — поле `completedAt`

**Files:**
- Modify: `app/src/main/java/com/valerochka1337/valerochkagym/data/db/entity/WorkoutSetEntity.kt`
- Modify: `app/src/main/java/com/valerochka1337/valerochkagym/data/db/GymDatabase.kt`
- Modify: `app/src/main/java/com/valerochka1337/valerochkagym/di/DataModule.kt`
- Test: `app/src/test/java/com/valerochka1337/valerochkagym/data/db/Migration1To2Test.kt`

- [ ] **Step 1: Добавить поле в сущность**

В `WorkoutSetEntity.kt` добавить поле после `isCompleted`:

```kotlin
data class WorkoutSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutExerciseId: Long,
    val setIndex: Int,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val durationSec: Int? = null,
    val speedKmh: Double? = null,
    val inclinePct: Double? = null,
    val isCompleted: Boolean = false,
    val completedAt: Long? = null,
)
```

- [ ] **Step 2: Поднять версию и объявить миграцию**

В `GymDatabase.kt` изменить `version = 1` на `version = 2` и добавить companion с миграцией. Импортировать `androidx.room.migration.Migration` и `androidx.sqlite.db.SupportSQLiteDatabase`:

```kotlin
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
```

```kotlin
    version = 2,
```

```kotlin
abstract class GymDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun routineDao(): RoutineDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun scheduledWorkoutDao(): ScheduledWorkoutDao

    companion object {
        /** v1 → v2: у подходов появляется момент отметки (nullable, старые строки → NULL). */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workout_sets ADD COLUMN completedAt INTEGER")
            }
        }
    }
}
```

- [ ] **Step 3: Зарегистрировать миграцию в DI**

В `DataModule.kt` в `provideDatabase` добавить `.addMigrations(GymDatabase.MIGRATION_1_2)`:

```kotlin
    fun provideDatabase(
        @ApplicationContext context: Context,
        callback: GymDatabaseCallback,
    ): GymDatabase =
        Room.databaseBuilder(context, GymDatabase::class.java, "gym.db")
            .addCallback(callback)
            .addMigrations(GymDatabase.MIGRATION_1_2)
            .build()
```

- [ ] **Step 4: Собрать проект — сгенерировать схему v2**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL; появляется файл `app/schemas/com.valerochka1337.valerochkagym.data.db.GymDatabase/2.json` (коммитится вместе с кодом).

- [ ] **Step 5: Написать миграционный тест**

Тест напрямую прогоняет объект `MIGRATION_1_2` над in-memory SQLite (без `MigrationTestHelper` и схем-ассетов — под Robolectric это надёжнее). Создать v1-таблицу `workout_sets` (DDL из `schemas/1.json`), вставить строку, применить миграцию, проверить, что колонка `completedAt` появилась и старая строка жива с `NULL`.

Создать `Migration1To2Test.kt`:

```kotlin
package com.valerochka1337.valerochkagym.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class Migration1To2Test {

    /** Точный DDL таблицы `workout_sets` версии 1 (см. schemas/1.json), без завёртки TABLE_NAME. */
    private val v1WorkoutSets =
        "CREATE TABLE `workout_sets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`workoutExerciseId` INTEGER NOT NULL, `setIndex` INTEGER NOT NULL, `weightKg` REAL, " +
            "`reps` INTEGER, `durationSec` INTEGER, `speedKmh` REAL, `inclinePct` REAL, " +
            "`isCompleted` INTEGER NOT NULL)"

    @Test
    fun `migration 1 to 2 adds nullable completedAt and keeps existing rows`() {
        val db = openInMemory { it.execSQL(v1WorkoutSets) }
        db.execSQL("INSERT INTO workout_sets (workoutExerciseId, setIndex, isCompleted) VALUES (1, 0, 1)")

        GymDatabase.MIGRATION_1_2.migrate(db)

        db.query("SELECT completedAt FROM workout_sets").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0)) // старая строка получает NULL
        }
        db.close()
    }

    /** In-memory SupportSQLiteDatabase; [onCreate] строит стартовую схему v1. */
    private fun openInMemory(onCreate: (SupportSQLiteDatabase) -> Unit): SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) = onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null) // in-memory
            .callback(callback)
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }
}
```

- [ ] **Step 6: Запустить тест**

Run: `./gradlew :app:testDebugUnitTest --tests "*Migration1To2Test*"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/valerochka1337/valerochkagym/data/db/entity/WorkoutSetEntity.kt \
        app/src/main/java/com/valerochka1337/valerochkagym/data/db/GymDatabase.kt \
        app/src/main/java/com/valerochka1337/valerochkagym/di/DataModule.kt \
        app/schemas \
        app/src/test/java/com/valerochka1337/valerochkagym/data/db/Migration1To2Test.kt
git commit -m "feat: миграция БД 1→2, поле workout_sets.completedAt"
```

---

### Task 2: Запись `completedAt` при отметке подхода

**Files:**
- Modify: `app/src/main/java/com/valerochka1337/valerochkagym/data/db/dao/WorkoutDao.kt:40-41`
- Modify: `app/src/main/java/com/valerochka1337/valerochkagym/data/ActiveWorkoutRepositoryImpl.kt:90-91`
- Test: `app/src/test/java/com/valerochka1337/valerochkagym/data/WorkoutDaoTest.kt`

- [ ] **Step 1: Написать падающий тест DAO**

В `WorkoutDaoTest.kt` добавить регион перед `private suspend fun addExercise`:

```kotlin
    // region completedAt

    @Test
    fun `setSetCompleted writes completedAt when completing and clears it when uncompleting`() =
        runTest {
            val exerciseId = addExercise()
            insertWorkout("w", startedAt = 1_000, finishedAt = null)
            val we = insertWorkoutExercise("w", exerciseId)
            val setId = insertSet(we, setIndex = 0, weightKg = 50.0, isCompleted = false)

            workoutDao.setSetCompleted(setId, completed = true, completedAt = 12_345L)
            assertEquals(12_345L, workoutDao.getSet(setId)!!.completedAt)

            workoutDao.setSetCompleted(setId, completed = false, completedAt = null)
            assertNull(workoutDao.getSet(setId)!!.completedAt)
        }

    // endregion
```

- [ ] **Step 2: Запустить — убедиться, что не компилируется/падает**

Run: `./gradlew :app:testDebugUnitTest --tests "*WorkoutDaoTest*"`
Expected: FAIL — `setSetCompleted` не принимает `completedAt`.

- [ ] **Step 3: Изменить DAO-запрос**

В `WorkoutDao.kt` заменить:

```kotlin
    @Query("UPDATE workout_sets SET isCompleted = :completed WHERE id = :setId")
    suspend fun setSetCompleted(setId: Long, completed: Boolean)
```

на:

```kotlin
    @Query("UPDATE workout_sets SET isCompleted = :completed, completedAt = :completedAt WHERE id = :setId")
    suspend fun setSetCompleted(setId: Long, completed: Boolean, completedAt: Long?)
```

- [ ] **Step 4: Прокинуть время из репозитория**

В `ActiveWorkoutRepositoryImpl.kt` заменить:

```kotlin
    override suspend fun toggleSetCompleted(setId: Long, completed: Boolean) =
        workoutDao.setSetCompleted(setId, completed)
```

на:

```kotlin
    override suspend fun toggleSetCompleted(setId: Long, completed: Boolean) =
        workoutDao.setSetCompleted(setId, completed, completedAt = if (completed) now() else null)
```

Интерфейс `ActiveWorkoutRepository.toggleSetCompleted(setId, completed)` НЕ меняем — фейки в тестах остаются валидны.

- [ ] **Step 5: Запустить тест**

Run: `./gradlew :app:testDebugUnitTest --tests "*WorkoutDaoTest*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/valerochka1337/valerochkagym/data/db/dao/WorkoutDao.kt \
        app/src/main/java/com/valerochka1337/valerochkagym/data/ActiveWorkoutRepositoryImpl.kt \
        app/src/test/java/com/valerochka1337/valerochkagym/data/WorkoutDaoTest.kt
git commit -m "feat: сохранять completedAt подхода при отметке"
```

---

### Task 3: Экспорт берёт честное время из `completedAt`

**Files:**
- Modify: `app/src/main/java/com/valerochka1337/valerochkagym/domain/WorkoutRowMapper.kt:55-97`
- Test: `app/src/test/java/com/valerochka1337/valerochkagym/domain/WorkoutRowMapperTest.kt`

- [ ] **Step 1: Обновить хелпер и добавить падающие тесты**

В `WorkoutRowMapperTest.kt` добавить параметр `completedAt` в хелпер `set(...)` (после `isCompleted`):

```kotlin
    private fun set(
        setIndex: Int,
        weightKg: Double? = null,
        reps: Int? = null,
        durationSec: Int? = null,
        speedKmh: Double? = null,
        inclinePct: Double? = null,
        isCompleted: Boolean,
        completedAt: Long? = null,
    ): WorkoutSetEntity = WorkoutSetEntity(
        workoutExerciseId = 0,
        setIndex = setIndex,
        weightKg = weightKg,
        reps = reps,
        durationSec = durationSec,
        speedKmh = speedKmh,
        inclinePct = inclinePct,
        isCompleted = isCompleted,
        completedAt = completedAt,
    )
```

Добавить два теста в регион `// region column contract`:

```kotlin
    @Test
    fun `row date and time come from set completedAt when present`() {
        val setTime = STARTED_AT + 3_600_000L // +1 час к старту тренировки
        val workout = workoutFull(
            startedAt = STARTED_AT,
            exercises = listOf(
                exercise(
                    name = "Присед",
                    muscleGroup = MuscleGroup.LEGS,
                    type = ExerciseType.STRENGTH,
                    position = 0,
                    sets = listOf(
                        set(setIndex = 0, weightKg = 100.0, reps = 5, isCompleted = true, completedAt = setTime),
                    ),
                ),
            ),
        )

        val row = WorkoutRowMapper.rows(workout).single()

        assertEquals(expectedDate(setTime), row[1])
        assertEquals(expectedTime(setTime), row[2])
    }

    @Test
    fun `row falls back to workout start when set completedAt is null`() {
        val workout = workoutFull(
            startedAt = STARTED_AT,
            exercises = listOf(
                exercise(
                    name = "Присед",
                    muscleGroup = MuscleGroup.LEGS,
                    type = ExerciseType.STRENGTH,
                    position = 0,
                    sets = listOf(
                        set(setIndex = 0, weightKg = 100.0, reps = 5, isCompleted = true, completedAt = null),
                    ),
                ),
            ),
        )

        val row = WorkoutRowMapper.rows(workout).single()

        assertEquals(expectedDate(STARTED_AT), row[1])
        assertEquals(expectedTime(STARTED_AT), row[2])
    }
```

- [ ] **Step 2: Запустить — убедиться, что новый кейс падает**

Run: `./gradlew :app:testDebugUnitTest --tests "*WorkoutRowMapperTest*"`
Expected: FAIL на `row date and time come from set completedAt when present` (сейчас берётся время старта тренировки).

- [ ] **Step 3: Перенести вычисление даты/времени на уровень подхода**

В `WorkoutRowMapper.kt` заменить тело `rows(...)`:

```kotlin
    /** Строки Sheets по выполненным подходам, упорядоченные по позиции упражнения и индексу подхода. */
    fun rows(workout: WorkoutFull): List<List<Any?>> {
        val zone = ZoneId.systemDefault()

        return workout.exercises
            .sortedBy { it.workoutExercise.position }
            .flatMap { exercise ->
                exercise.sets
                    .filter { it.isCompleted }
                    .sortedBy { it.setIndex }
                    .map { set ->
                        // Время строки — момент отметки подхода; для «легаси»-подходов без
                        // completedAt откатываемся на время старта тренировки.
                        val instant = Instant.ofEpochMilli(set.completedAt ?: workout.workout.startedAt)
                        val zoned = instant.atZone(zone)
                        val date = DATE_FORMATTER.format(zoned)
                        val startTime = TIME_FORMATTER.format(zoned)
                        val volume = if (
                            exercise.exercise.type == ExerciseType.STRENGTH &&
                            set.weightKg != null &&
                            set.reps != null
                        ) {
                            set.weightKg * set.reps
                        } else {
                            null
                        }
                        listOf(
                            workout.workout.id,
                            date,
                            startTime,
                            workout.workout.name,
                            exercise.exercise.name,
                            // Экспорт намеренно использует русские UI-названия;
                            // изменение displayName() меняет семантику исторических выгрузок.
                            exercise.exercise.muscleGroup.displayName(),
                            exercise.exercise.type.displayName(),
                            set.setIndex + 1,
                            set.weightKg,
                            set.reps,
                            set.durationSec,
                            set.speedKmh,
                            set.inclinePct,
                            volume,
                        )
                    }
            }
    }
```

Обновить KDoc колонок `date`/`start_time` в шапке файла: заменить упоминание «начала тренировки» на «момент отметки подхода (`completedAt`), фоллбэк — старт тренировки». Строки в KDoc:

```
 *  - `date`         — дата отметки подхода `yyyy-MM-dd` в локальной таймзоне (фоллбэк — старт тренировки).
 *  - `start_time`   — время отметки подхода `HH:mm` в локальной таймзоне (фоллбэк — старт тренировки).
```

- [ ] **Step 4: Запустить тесты**

Run: `./gradlew :app:testDebugUnitTest --tests "*WorkoutRowMapperTest*"`
Expected: PASS (все, включая старые — у них `completedAt == null`, фоллбэк даёт прежние значения).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valerochka1337/valerochkagym/domain/WorkoutRowMapper.kt \
        app/src/test/java/com/valerochka1337/valerochkagym/domain/WorkoutRowMapperTest.kt
git commit -m "feat: экспорт пишет время каждого подхода из completedAt"
```

---

### Task 4: Обратный маппинг RU-названий в enum

**Files:**
- Modify: `app/src/main/java/com/valerochka1337/valerochkagym/domain/EnumDisplay.kt`
- Test: `app/src/test/java/com/valerochka1337/valerochkagym/domain/EnumDisplayTest.kt`

- [ ] **Step 1: Написать падающий тест**

Создать `EnumDisplayTest.kt`:

```kotlin
package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class EnumDisplayTest {

    @Test
    fun `muscleGroupFrom is the inverse of displayName for every value`() {
        MuscleGroup.entries.forEach { group ->
            assertEquals(group, muscleGroupFrom(group.displayName()))
        }
    }

    @Test
    fun `exerciseTypeFrom is the inverse of displayName for every value`() {
        ExerciseType.entries.forEach { type ->
            assertEquals(type, exerciseTypeFrom(type.displayName()))
        }
    }

    @Test
    fun `muscleGroupFrom trims and ignores case`() {
        assertEquals(MuscleGroup.CHEST, muscleGroupFrom("  грудь "))
    }

    @Test
    fun `unknown muscle group falls back to FULL_BODY`() {
        assertEquals(MuscleGroup.FULL_BODY, muscleGroupFrom("абракадабра"))
    }

    @Test
    fun `unknown exercise type falls back to STRENGTH`() {
        assertEquals(ExerciseType.STRENGTH, exerciseTypeFrom("что-то"))
    }
}
```

- [ ] **Step 2: Запустить — убедиться, что не компилируется**

Run: `./gradlew :app:testDebugUnitTest --tests "*EnumDisplayTest*"`
Expected: FAIL — `muscleGroupFrom`/`exerciseTypeFrom` не существуют.

- [ ] **Step 3: Реализовать обратные функции**

В конец `EnumDisplay.kt` добавить:

```kotlin
/**
 * Разбирает русское имя группы мышц обратно в [MuscleGroup] (обратное к [displayName]).
 * Сравнение регистронезависимо и без пробелов по краям. Неизвестная метка → [MuscleGroup.FULL_BODY],
 * чтобы импорт из таблицы не падал на нестандартных значениях.
 */
fun muscleGroupFrom(label: String): MuscleGroup {
    val normalized = label.trim().lowercase()
    return MuscleGroup.entries.firstOrNull { it.displayName().lowercase() == normalized }
        ?: MuscleGroup.FULL_BODY
}

/**
 * Разбирает русское имя типа упражнения обратно в [ExerciseType] (обратное к [displayName]).
 * Регистронезависимо, с фоллбэком на [ExerciseType.STRENGTH] для неизвестных меток.
 */
fun exerciseTypeFrom(label: String): ExerciseType {
    val normalized = label.trim().lowercase()
    return ExerciseType.entries.firstOrNull { it.displayName().lowercase() == normalized }
        ?: ExerciseType.STRENGTH
}
```

- [ ] **Step 4: Запустить тест**

Run: `./gradlew :app:testDebugUnitTest --tests "*EnumDisplayTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valerochka1337/valerochkagym/domain/EnumDisplay.kt \
        app/src/test/java/com/valerochka1337/valerochkagym/domain/EnumDisplayTest.kt
git commit -m "feat: обратный маппинг RU-названий группы и типа в enum"
```

---

### Task 5: Парсер строк листа `WorkoutRowParser`

**Files:**
- Create: `app/src/main/java/com/valerochka1337/valerochkagym/domain/WorkoutRowParser.kt`
- Test: `app/src/test/java/com/valerochka1337/valerochkagym/domain/WorkoutRowParserTest.kt`

- [ ] **Step 1: Написать падающие тесты (включая round-trip с экспортом)**

Создать `WorkoutRowParserTest.kt`:

```kotlin
package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutExerciseWithSets
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutRowParserTest {

    // region round-trip with the exporter

    @Test
    fun `round-trips a workout exported by WorkoutRowMapper`() {
        // Времена подходов на границе минут, чтобы усечение до HH:mm давало точное равенство.
        val minute = 60_000L
        val base = 1_700_000_000_000L / minute * minute
        val workout = WorkoutFull(
            workout = WorkoutEntity(id = "w-1", name = "Ноги", startedAt = base),
            exercises = listOf(
                exercise("Присед", MuscleGroup.LEGS, ExerciseType.STRENGTH, position = 0, sets = listOf(
                    set(0, weightKg = 100.0, reps = 5, completedAt = base),
                    set(1, weightKg = 105.0, reps = 3, completedAt = base + minute),
                )),
                exercise("Планка", MuscleGroup.CORE, ExerciseType.TIMED, position = 1, sets = listOf(
                    set(0, durationSec = 60, completedAt = base + 2 * minute),
                )),
            ),
        )

        val rows = listOf(WorkoutRowMapper.HEADER_ROW) + WorkoutRowMapper.rows(workout).map(::toStrings)
        val parsed = WorkoutRowParser.parse(rows)

        assertEquals(1, parsed.size)
        val pw = parsed.single()
        assertEquals("w-1", pw.id)
        assertEquals("Ноги", pw.name)
        assertEquals(base, pw.startedAt)          // минимум completedAt
        assertEquals(base + 2 * minute, pw.finishedAt) // максимум completedAt
        assertEquals(listOf("Присед", "Планка"), pw.exercises.map { it.name })
        assertEquals(listOf(0, 1), pw.exercises.map { it.position })

        val squat = pw.exercises[0]
        assertEquals(MuscleGroup.LEGS, squat.muscleGroup)
        assertEquals(ExerciseType.STRENGTH, squat.type)
        assertEquals(listOf(0, 1), squat.sets.map { it.setIndex })
        assertEquals(100.0, squat.sets[0].weightKg!!, 0.0)
        assertEquals(5, squat.sets[0].reps)
        assertEquals(base, squat.sets[0].completedAt)

        val plank = pw.exercises[1]
        assertEquals(ExerciseType.TIMED, plank.type)
        assertEquals(60, plank.sets.single().durationSec)
        assertNull(plank.sets.single().weightKg)
    }

    // endregion

    // region robustness

    @Test
    fun `skips the header row and blank rows`() {
        val rows = listOf(
            WorkoutRowMapper.HEADER_ROW,
            emptyList(),
            row(workoutId = "", exercise = "Присед"), // пустой workout_id
            row(workoutId = "w", date = "2026-01-02", time = "10:00", name = "T",
                exercise = "Присед", muscle = "Ноги", type = "Силовое", setIndex = "1", weight = "80"),
        )

        val parsed = WorkoutRowParser.parse(rows)

        assertEquals(1, parsed.size)
        assertEquals("w", parsed.single().id)
    }

    @Test
    fun `parses numeric fields and leaves empty cells null`() {
        val rows = listOf(
            row(workoutId = "w", date = "2026-01-02", time = "10:00", name = "T",
                exercise = "Дорожка", muscle = "Кардио", type = "Кардио", setIndex = "1",
                weight = "", reps = "", duration = "600", speed = "10.5", incline = "5"),
        )

        val s = WorkoutRowParser.parse(rows).single().exercises.single().sets.single()

        assertNull(s.weightKg)
        assertNull(s.reps)
        assertEquals(600, s.durationSec)
        assertEquals(10.5, s.speedKmh!!, 0.0)
        assertEquals(5.0, s.inclinePct!!, 0.0)
    }

    @Test
    fun `accepts comma decimal separator`() {
        val rows = listOf(
            row(workoutId = "w", date = "2026-01-02", time = "10:00", name = "T",
                exercise = "Присед", muscle = "Ноги", type = "Силовое", setIndex = "1", weight = "100,5", reps = "5"),
        )

        assertEquals(100.5, WorkoutRowParser.parse(rows).single().exercises.single().sets.single().weightKg!!, 0.0)
    }

    @Test
    fun `unknown russian labels fall back`() {
        val rows = listOf(
            row(workoutId = "w", date = "2026-01-02", time = "10:00", name = "T",
                exercise = "Странное", muscle = "???", type = "???", setIndex = "1", weight = "10", reps = "1"),
        )

        val ex = WorkoutRowParser.parse(rows).single().exercises.single()
        assertEquals(MuscleGroup.FULL_BODY, ex.muscleGroup)
        assertEquals(ExerciseType.STRENGTH, ex.type)
    }

    @Test
    fun `groups rows into two workouts preserving order`() {
        val rows = listOf(
            row(workoutId = "a", date = "2026-01-02", time = "10:00", name = "A",
                exercise = "Присед", muscle = "Ноги", type = "Силовое", setIndex = "1", weight = "80"),
            row(workoutId = "b", date = "2026-01-03", time = "11:00", name = "B",
                exercise = "Жим", muscle = "Грудь", type = "Силовое", setIndex = "1", weight = "60"),
        )

        assertEquals(listOf("a", "b"), WorkoutRowParser.parse(rows).map { it.id })
    }

    @Test
    fun `empty input yields no workouts`() {
        assertTrue(WorkoutRowParser.parse(emptyList()).isEmpty())
    }

    // endregion

    // region helpers

    private fun toStrings(cells: List<Any?>): List<String> = cells.map { it?.toString() ?: "" }

    /** Строка листа в порядке HEADER_ROW; незаданные поля — пустые. */
    private fun row(
        workoutId: String,
        date: String = "",
        time: String = "",
        name: String = "",
        exercise: String = "",
        muscle: String = "",
        type: String = "",
        setIndex: String = "",
        weight: String = "",
        reps: String = "",
        duration: String = "",
        speed: String = "",
        incline: String = "",
        volume: String = "",
    ): List<String> = listOf(
        workoutId, date, time, name, exercise, muscle, type, setIndex,
        weight, reps, duration, speed, incline, volume,
    )

    private fun exercise(
        name: String,
        muscleGroup: MuscleGroup,
        type: ExerciseType,
        position: Int,
        sets: List<WorkoutSetEntity>,
    ): WorkoutExerciseWithSets = WorkoutExerciseWithSets(
        workoutExercise = WorkoutExerciseEntity(id = 0, workoutId = "w-1", exerciseId = 0, position = position),
        exercise = ExerciseEntity(name = name, muscleGroup = muscleGroup, type = type),
        sets = sets,
    )

    private fun set(
        setIndex: Int,
        weightKg: Double? = null,
        reps: Int? = null,
        durationSec: Int? = null,
        speedKmh: Double? = null,
        inclinePct: Double? = null,
        completedAt: Long,
    ): WorkoutSetEntity = WorkoutSetEntity(
        workoutExerciseId = 0,
        setIndex = setIndex,
        weightKg = weightKg,
        reps = reps,
        durationSec = durationSec,
        speedKmh = speedKmh,
        inclinePct = inclinePct,
        isCompleted = true,
        completedAt = completedAt,
    )

    // endregion
}
```

- [ ] **Step 2: Запустить — убедиться, что не компилируется**

Run: `./gradlew :app:testDebugUnitTest --tests "*WorkoutRowParserTest*"`
Expected: FAIL — `WorkoutRowParser` не существует.

- [ ] **Step 3: Реализовать парсер**

Создать `WorkoutRowParser.kt`:

```kotlin
package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Разобранная тренировка из листа `Workouts`. Времена — epoch millis в системной таймзоне. */
data class ParsedWorkout(
    val id: String,
    val name: String,
    val startedAt: Long,
    val finishedAt: Long,
    val exercises: List<ParsedExercise>,
)

data class ParsedExercise(
    val name: String,
    val muscleGroup: MuscleGroup,
    val type: ExerciseType,
    val position: Int,
    val sets: List<ParsedSet>,
)

data class ParsedSet(
    val setIndex: Int,
    val weightKg: Double?,
    val reps: Int?,
    val durationSec: Int?,
    val speedKmh: Double?,
    val inclinePct: Double?,
    val completedAt: Long,
)

/**
 * Обратный к [WorkoutRowMapper]: собирает плоские строки листа `Workouts` (в порядке
 * [WorkoutRowMapper.HEADER_ROW]) в дерево тренировок.
 *
 * Пропускает строку-шапку, пустые строки и строки без `workout_id` или с нераспознанным
 * временем (`date`+`start_time`). Строки группируются по `workout_id` (порядок появления
 * сохраняется), внутри — по имени упражнения в порядке первого появления (это даёт `position`).
 * `startedAt`/`finishedAt` тренировки — минимум/максимум `completedAt` её подходов. Числа
 * парсятся мягко (запятая-десятичный разделитель допускается); пустая ячейка → `null`.
 */
object WorkoutRowParser {

    private val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private const val COL_WORKOUT_ID = 0
    private const val COL_DATE = 1
    private const val COL_START_TIME = 2
    private const val COL_WORKOUT_NAME = 3
    private const val COL_EXERCISE = 4
    private const val COL_MUSCLE_GROUP = 5
    private const val COL_TYPE = 6
    private const val COL_SET_INDEX = 7
    private const val COL_WEIGHT = 8
    private const val COL_REPS = 9
    private const val COL_DURATION = 10
    private const val COL_SPEED = 11
    private const val COL_INCLINE = 12

    fun parse(rows: List<List<String>>): List<ParsedWorkout> {
        val zone = ZoneId.systemDefault()

        // Сырые подходы, сгруппированные по workout_id с сохранением порядка появления.
        data class RawSet(
            val exercise: String,
            val muscleGroup: MuscleGroup,
            val type: ExerciseType,
            val setIndex: Int,
            val weightKg: Double?,
            val reps: Int?,
            val durationSec: Int?,
            val speedKmh: Double?,
            val inclinePct: Double?,
            val completedAt: Long,
        )

        val grouped = LinkedHashMap<String, Pair<String, MutableList<RawSet>>>()

        for (row in rows) {
            val id = row.cell(COL_WORKOUT_ID)
            if (id.isEmpty() || id == "workout_id") continue // пропуск шапки/пустых
            val millis = parseMillis(row.cell(COL_DATE), row.cell(COL_START_TIME), zone) ?: continue

            val (_, sets) = grouped.getOrPut(id) { row.cell(COL_WORKOUT_NAME) to mutableListOf() }
            sets.add(
                RawSet(
                    exercise = row.cell(COL_EXERCISE),
                    muscleGroup = muscleGroupFrom(row.cell(COL_MUSCLE_GROUP)),
                    type = exerciseTypeFrom(row.cell(COL_TYPE)),
                    setIndex = (row.cell(COL_SET_INDEX).toIntOrNull() ?: (sets.size + 1)) - 1,
                    weightKg = row.cell(COL_WEIGHT).toDoubleLoose(),
                    reps = row.cell(COL_REPS).toIntOrNull(),
                    durationSec = row.cell(COL_DURATION).toIntOrNull(),
                    speedKmh = row.cell(COL_SPEED).toDoubleLoose(),
                    inclinePct = row.cell(COL_INCLINE).toDoubleLoose(),
                    completedAt = millis,
                ),
            )
        }

        return grouped.map { (id, value) ->
            val (name, raws) = value
            val times = raws.map { it.completedAt }
            val exercises = LinkedHashMap<String, MutableList<RawSet>>()
            raws.forEach { exercises.getOrPut(it.exercise) { mutableListOf() }.add(it) }

            ParsedWorkout(
                id = id,
                name = name,
                startedAt = times.minOrNull()!!, // группа непустая: у неё ≥1 подход
                finishedAt = times.maxOrNull()!!,
                exercises = exercises.entries.mapIndexed { position, (exerciseName, exerciseSets) ->
                    val first = exerciseSets.first()
                    ParsedExercise(
                        name = exerciseName,
                        muscleGroup = first.muscleGroup,
                        type = first.type,
                        position = position,
                        sets = exerciseSets.map { s ->
                            ParsedSet(
                                setIndex = s.setIndex,
                                weightKg = s.weightKg,
                                reps = s.reps,
                                durationSec = s.durationSec,
                                speedKmh = s.speedKmh,
                                inclinePct = s.inclinePct,
                                completedAt = s.completedAt,
                            )
                        },
                    )
                },
            )
        }
    }

    private fun List<String>.cell(index: Int): String = getOrNull(index)?.trim().orEmpty()

    /** Мягкий разбор Double: пустая строка → null; запятая-разделитель приводится к точке. */
    private fun String.toDoubleLoose(): Double? =
        takeIf { it.isNotEmpty() }?.replace(',', '.')?.toDoubleOrNull()

    private fun parseMillis(date: String, time: String, zone: ZoneId): Long? {
        if (date.isEmpty() || time.isEmpty()) return null
        return try {
            LocalDateTime.parse("$date $time", DATE_TIME_FORMATTER)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        } catch (e: java.time.format.DateTimeParseException) {
            null
        }
    }
}
```

- [ ] **Step 4: Запустить тесты**

Run: `./gradlew :app:testDebugUnitTest --tests "*WorkoutRowParserTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valerochka1337/valerochkagym/domain/WorkoutRowParser.kt \
        app/src/test/java/com/valerochka1337/valerochkagym/domain/WorkoutRowParserTest.kt
git commit -m "feat: WorkoutRowParser — разбор листа Workouts в дерево тренировок"
```

---

### Task 6: DAO-хелперы для импорта

**Files:**
- Modify: `app/src/main/java/com/valerochka1337/valerochkagym/data/db/dao/WorkoutDao.kt`
- Modify: `app/src/main/java/com/valerochka1337/valerochkagym/data/db/dao/ExerciseDao.kt`
- Test: `app/src/test/java/com/valerochka1337/valerochkagym/data/WorkoutDaoTest.kt`
- Test: `app/src/test/java/com/valerochka1337/valerochkagym/data/ExerciseDaoTest.kt`

- [ ] **Step 1: Написать падающие тесты**

В `WorkoutDaoTest.kt` в регион `// region completedAt` (или новый регион) добавить:

```kotlin
    @Test
    fun `getExistingWorkoutIds returns all workout ids`() = runTest {
        insertWorkout("a", startedAt = 1_000, finishedAt = 2_000)
        insertWorkout("b", startedAt = 3_000, finishedAt = null)

        assertEquals(setOf("a", "b"), workoutDao.getExistingWorkoutIds().toSet())
    }

    @Test
    fun `getExistingWorkoutIds is empty without workouts`() = runTest {
        assertTrue(workoutDao.getExistingWorkoutIds().isEmpty())
    }
```

В `ExerciseDaoTest.kt` (если файла нет — создать по образцу `WorkoutDaoTest`, extends `RoomDaoTest`) добавить тест. Полный файл, если создаётся:

```kotlin
package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ExerciseDaoTest : RoomDaoTest() {

    private lateinit var exerciseDao: ExerciseDao

    @Before
    fun grabDao() {
        exerciseDao = db.exerciseDao()
    }

    @Test
    fun `findByName matches case-insensitively`() = runTest {
        exerciseDao.insert(
            ExerciseEntity(name = "Жим лёжа", muscleGroup = MuscleGroup.CHEST, type = ExerciseType.STRENGTH),
        )

        assertEquals("Жим лёжа", exerciseDao.findByName("жим лёжа")?.name)
    }

    @Test
    fun `findByName is null when nothing matches`() = runTest {
        assertNull(exerciseDao.findByName("Присед"))
    }
}
```

Если `ExerciseDaoTest.kt` уже существует — добавить только два теста выше внутрь класса.

- [ ] **Step 2: Запустить — убедиться, что не компилируется**

Run: `./gradlew :app:testDebugUnitTest --tests "*WorkoutDaoTest*" --tests "*ExerciseDaoTest*"`
Expected: FAIL — методы отсутствуют.

- [ ] **Step 3: Добавить методы в DAO**

В `WorkoutDao.kt` добавить (рядом с `getFinishedNotUploaded`):

```kotlin
    /** Id всех тренировок (для дедупликации импорта по `workout_id`). */
    @Query("SELECT id FROM workouts")
    suspend fun getExistingWorkoutIds(): List<String>
```

В `ExerciseDao.kt` добавить (рядом с `getById`):

```kotlin
    /** Первое упражнение с таким именем без учёта регистра (матчинг при импорте). */
    @Query("SELECT * FROM exercises WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): ExerciseEntity?
```

- [ ] **Step 4: Запустить тесты**

Run: `./gradlew :app:testDebugUnitTest --tests "*WorkoutDaoTest*" --tests "*ExerciseDaoTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valerochka1337/valerochkagym/data/db/dao/WorkoutDao.kt \
        app/src/main/java/com/valerochka1337/valerochkagym/data/db/dao/ExerciseDao.kt \
        app/src/test/java/com/valerochka1337/valerochkagym/data/WorkoutDaoTest.kt \
        app/src/test/java/com/valerochka1337/valerochkagym/data/ExerciseDaoTest.kt
git commit -m "feat: DAO getExistingWorkoutIds и findByName для импорта"
```

---

### Task 7: Репозиторий импорта `WorkoutImportRepository`

**Files:**
- Create: `app/src/main/java/com/valerochka1337/valerochkagym/data/google/WorkoutImportRepository.kt`
- Test: `app/src/test/java/com/valerochka1337/valerochkagym/data/WorkoutImportRepositoryTest.kt`

- [ ] **Step 1: Написать падающие тесты**

Создать `WorkoutImportRepositoryTest.kt` (наследует `RoomDaoTest`, чтобы писать в реальную БД; Google-сторона фейкается по образцу `SheetsRepositoryTest`):

```kotlin
package com.valerochka1337.valerochkagym.data

import android.app.Activity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.google.AuthorizeOutcome
import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.ImportResult
import com.valerochka1337.valerochkagym.data.google.SheetsApi
import com.valerochka1337.valerochkagym.data.google.SheetDto
import com.valerochka1337.valerochkagym.data.google.SheetPropertiesDto
import com.valerochka1337.valerochkagym.data.google.SpreadsheetDto
import com.valerochka1337.valerochkagym.data.google.TokenResult
import com.valerochka1337.valerochkagym.data.google.ValueRangeDto
import com.valerochka1337.valerochkagym.data.google.WorkoutImportRepositoryImpl
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.WorkoutRowMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class WorkoutImportRepositoryTest : RoomDaoTest() {

    private val header = WorkoutRowMapper.HEADER_ROW
    private fun dataRow(
        id: String, date: String, time: String, name: String, exercise: String,
        muscle: String, type: String, setIndex: String, weight: String = "", reps: String = "",
    ) = listOf(id, date, time, name, exercise, muscle, type, setIndex, weight, reps, "", "", "", "")

    @Test
    fun `imports new workouts and marks them UPLOADED with honest finish`() = runTest {
        val api = FakeSheetsApi(
            sheets = mutableListOf("Workouts"),
            values = mutableListOf(
                header,
                dataRow("w-1", "2026-01-02", "10:00", "Ноги", "Присед", "Ноги", "Силовое", "1", "100", "5"),
                dataRow("w-1", "2026-01-02", "10:05", "Ноги", "Присед", "Ноги", "Силовое", "2", "105", "3"),
            ),
        )

        val result = repository(api).importAll()

        assertEquals(ImportResult.Success(1), result)
        val full = workoutFull("w-1")
        assertEquals(UploadStatus.UPLOADED, full.workout.uploadStatus)
        // finishedAt = максимум времён подходов (10:05) > startedAt (10:00)
        assertTrue(full.workout.finishedAt!! > full.workout.startedAt)
        assertEquals(2, full.exercises.single().sets.size)
    }

    @Test
    fun `matches existing exercise by name and creates missing ones`() = runTest {
        db.exerciseDao().insert(
            ExerciseEntity(name = "Присед", muscleGroup = MuscleGroup.LEGS, type = ExerciseType.STRENGTH),
        )
        val api = FakeSheetsApi(
            sheets = mutableListOf("Workouts"),
            values = mutableListOf(
                header,
                dataRow("w-1", "2026-01-02", "10:00", "T", "Присед", "Ноги", "Силовое", "1", "100", "5"),
                dataRow("w-1", "2026-01-02", "10:05", "T", "Жим", "Грудь", "Силовое", "1", "60", "8"),
            ),
        )

        repository(api).importAll()

        // Присед переиспользован (1), Жим создан (2) → всего 2 упражнения.
        assertEquals(2, tableCount("exercises"))
    }

    @Test
    fun `skips workouts already present locally`() = runTest {
        insertWorkout("w-1", startedAt = 1_000, finishedAt = 2_000)
        val api = FakeSheetsApi(
            sheets = mutableListOf("Workouts"),
            values = mutableListOf(
                header,
                dataRow("w-1", "2026-01-02", "10:00", "T", "Присед", "Ноги", "Силовое", "1", "100", "5"),
            ),
        )

        assertEquals(ImportResult.NothingToImport, repository(api).importAll())
    }

    @Test
    fun `missing Workouts sheet is nothing to import`() = runTest {
        val api = FakeSheetsApi(sheets = mutableListOf(), values = mutableListOf())
        assertEquals(ImportResult.NothingToImport, repository(api).importAll())
    }

    @Test
    fun `missing spreadsheet id is a failure`() = runTest {
        val result = repository(FakeSheetsApi(), settings = settingsRepository(null)).importAll()
        assertTrue(result is ImportResult.Failure)
    }

    @Test
    fun `401 is a failure`() = runTest {
        val api = FakeSheetsApi(sheets = mutableListOf("Workouts"), failGetValues = httpException(401))
        assertTrue(repository(api).importAll() is ImportResult.Failure)
    }

    @Test
    fun `IOException is a failure`() = runTest {
        val api = FakeSheetsApi(sheets = mutableListOf("Workouts"), failGetValues = IOException("net"))
        assertTrue(repository(api).importAll() is ImportResult.Failure)
    }

    // region helpers

    private fun repository(
        api: FakeSheetsApi,
        auth: GoogleAuth = FakeGoogleAuth(TokenResult.Success("token")),
        settings: SettingsRepository = settingsRepository(SPREADSHEET_ID),
    ): WorkoutImportRepositoryImpl =
        WorkoutImportRepositoryImpl(api, auth, settings, db, db.workoutDao(), db.exerciseDao())

    private fun settingsRepository(spreadsheetId: String?): SettingsRepository {
        val prefs = if (spreadsheetId == null) emptyPreferences()
        else mutablePreferencesOf(stringPreferencesKey("spreadsheet_id") to spreadsheetId)
        return SettingsRepository(FakeDataStore(prefs))
    }

    private fun httpException(code: Int): HttpException =
        HttpException(Response.error<Unit>(code, "".toResponseBody()))

    private class FakeSheetsApi(
        val sheets: MutableList<String> = mutableListOf(),
        private val values: MutableList<List<String>> = mutableListOf(),
        private val failGetSpreadsheet: Exception? = null,
        private val failGetValues: Exception? = null,
    ) : SheetsApi {
        override suspend fun getSpreadsheet(bearer: String, spreadsheetId: String, fields: String): SpreadsheetDto {
            failGetSpreadsheet?.let { throw it }
            return SpreadsheetDto(sheets.map { SheetDto(SheetPropertiesDto(it)) })
        }
        override suspend fun batchUpdate(bearer: String, spreadsheetId: String, body: com.valerochka1337.valerochkagym.data.google.BatchUpdateRequestDto): JsonElement = JsonNull
        override suspend fun getValues(bearer: String, spreadsheetId: String, range: String): ValueRangeDto {
            failGetValues?.let { throw it }
            return ValueRangeDto(values = values.ifEmpty { null })
        }
        override suspend fun appendValues(bearer: String, spreadsheetId: String, range: String, body: com.valerochka1337.valerochkagym.data.google.AppendValuesDto, valueInputOption: String, insertDataOption: String): JsonElement = JsonNull
    }

    private class FakeGoogleAuth(private val token: TokenResult) : GoogleAuth {
        override suspend fun signIn(activity: Activity): Result<String> = Result.success("u@e.com")
        override suspend fun authorize(activity: Activity): AuthorizeOutcome = AuthorizeOutcome.Granted
        override suspend fun getAccessToken(): TokenResult = token
        override suspend fun signOut() = Unit
    }

    private class FakeDataStore(prefs: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(prefs)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            state.value = transform(state.value); return state.value
        }
    }

    // endregion

    private companion object {
        const val SPREADSHEET_ID = "1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms"
    }
}
```

Note: `ValueRangeDto.values` имеет тип `List<List<String>>?` — заголовок/строки уже строки, подходит напрямую.

- [ ] **Step 2: Запустить — убедиться, что не компилируется**

Run: `./gradlew :app:testDebugUnitTest --tests "*WorkoutImportRepositoryTest*"`
Expected: FAIL — `WorkoutImportRepositoryImpl`/`ImportResult` не существуют.

- [ ] **Step 3: Реализовать репозиторий**

Создать `WorkoutImportRepository.kt`:

```kotlin
package com.valerochka1337.valerochkagym.data.google

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.ParsedWorkout
import com.valerochka1337.valerochkagym.domain.WorkoutRowParser
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/**
 * Результат разового импорта истории из листа `Workouts`.
 *
 * [Success] — импортировано [imported] новых тренировок. [NothingToImport] — импортировать
 * нечего (нет листа/строк, или все тренировки уже есть локально). [Failure] — ошибка
 * ([reason] показывается пользователю).
 */
sealed interface ImportResult {
    data class Success(val imported: Int) : ImportResult
    data object NothingToImport : ImportResult
    data class Failure(val reason: String) : ImportResult
}

/** Разовый импорт истории тренировок из целевой Google-таблицы (обратный к выгрузке). */
interface WorkoutImportRepository {
    suspend fun importAll(): ImportResult
}

/**
 * Читает лист `Workouts` целевой таблицы и вставляет в БД только те тренировки, которых ещё
 * нет локально (дедуп по `workout_id`). Владеет всем флоу (настройки → токен → чтение →
 * разбор → вставка), как [SheetsRepositoryImpl] владеет `uploadWorkout`. Упражнения матчатся
 * по имени, отсутствующие создаются как `isCustom = true`. Импортированные тренировки
 * помечаются [UploadStatus.UPLOADED] — они уже в таблице и не должны выгружаться обратно.
 */
class WorkoutImportRepositoryImpl @Inject constructor(
    private val api: SheetsApi,
    private val googleAuth: GoogleAuth,
    private val settingsRepository: SettingsRepository,
    private val database: GymDatabase,
    private val workoutDao: WorkoutDao,
    private val exerciseDao: ExerciseDao,
) : WorkoutImportRepository {

    override suspend fun importAll(): ImportResult {
        val spreadsheetId = settingsRepository.settings.first().spreadsheetId
            ?: return ImportResult.Failure("Укажите таблицу в настройках")

        val token = when (val result = googleAuth.getAccessToken()) {
            is TokenResult.Success -> result.token
            TokenResult.NeedsConsent -> return ImportResult.Failure("Настройте доступ к Google в настройках")
            is TokenResult.Failed -> return ImportResult.Failure(GoogleErrorMessages.NO_CONNECTION)
        }

        val bearer = "Bearer $token"
        return try {
            if (!workoutsSheetExists(bearer, spreadsheetId)) return ImportResult.NothingToImport
            val values = api.getValues(bearer, spreadsheetId, WORKOUTS_RANGE).values
                ?: return ImportResult.NothingToImport
            val parsed = WorkoutRowParser.parse(values)
            if (parsed.isEmpty()) return ImportResult.NothingToImport

            val existing = workoutDao.getExistingWorkoutIds().toSet()
            val fresh = parsed.filter { it.id !in existing }
            if (fresh.isEmpty()) return ImportResult.NothingToImport

            fresh.forEach { insertWorkout(it) }
            ImportResult.Success(fresh.size)
        } catch (e: HttpException) {
            ImportResult.Failure(classifyHttp(e.code()))
        } catch (e: IOException) {
            ImportResult.Failure(GoogleErrorMessages.NO_NETWORK)
        }
    }

    private suspend fun workoutsSheetExists(bearer: String, spreadsheetId: String): Boolean =
        api.getSpreadsheet(bearer, spreadsheetId).sheets.any { it.properties.title == WORKOUTS_SHEET }

    private suspend fun insertWorkout(parsed: ParsedWorkout) = database.withTransaction {
        workoutDao.insertWorkout(
            WorkoutEntity(
                id = parsed.id,
                routineId = null,
                name = parsed.name,
                startedAt = parsed.startedAt,
                finishedAt = parsed.finishedAt,
                uploadStatus = UploadStatus.UPLOADED,
                uploadError = null,
            ),
        )
        for (exercise in parsed.exercises) {
            val exerciseId = exerciseDao.findByName(exercise.name)?.id
                ?: exerciseDao.insert(
                    ExerciseEntity(
                        name = exercise.name,
                        muscleGroup = exercise.muscleGroup,
                        type = exercise.type,
                        isCustom = true,
                    ),
                )
            val workoutExerciseId = workoutDao.insertWorkoutExercise(
                WorkoutExerciseEntity(
                    workoutId = parsed.id,
                    exerciseId = exerciseId,
                    position = exercise.position,
                ),
            )
            if (exercise.sets.isNotEmpty()) {
                workoutDao.insertSets(
                    exercise.sets.map { set ->
                        WorkoutSetEntity(
                            workoutExerciseId = workoutExerciseId,
                            setIndex = set.setIndex,
                            weightKg = set.weightKg,
                            reps = set.reps,
                            durationSec = set.durationSec,
                            speedKmh = set.speedKmh,
                            inclinePct = set.inclinePct,
                            isCompleted = true,
                            completedAt = set.completedAt,
                        )
                    },
                )
            }
        }
    }

    /** Те же формулировки, что при выгрузке (см. `SheetsRepositoryImpl.classifyHttp`). */
    private fun classifyHttp(code: Int): String = when (code) {
        401, 403 -> "Нет доступа к таблице — проверьте вход и права"
        404 -> "Таблица не найдена — проверьте ссылку"
        429 -> "Слишком много запросов (HTTP 429)"
        in 500..599 -> "Ошибка сервера (HTTP $code)"
        else -> "Ошибка запроса (HTTP $code)"
    }

    private companion object {
        const val WORKOUTS_SHEET = "Workouts"
        /** Весь лист «Workouts» (14 колонок A–N, см. WorkoutRowMapper.HEADER_ROW). */
        const val WORKOUTS_RANGE = "Workouts!A:N"
    }
}
```

- [ ] **Step 4: Запустить тесты**

Run: `./gradlew :app:testDebugUnitTest --tests "*WorkoutImportRepositoryTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valerochka1337/valerochkagym/data/google/WorkoutImportRepository.kt \
        app/src/test/java/com/valerochka1337/valerochkagym/data/WorkoutImportRepositoryTest.kt
git commit -m "feat: WorkoutImportRepository — импорт истории из листа Workouts"
```

---

### Task 8: DI-биндинг репозитория импорта

**Files:**
- Modify: `app/src/main/java/com/valerochka1337/valerochkagym/di/GoogleModule.kt`

- [ ] **Step 1: Добавить биндинг**

В `GoogleModule.kt` добавить импорты и `@Binds`:

```kotlin
import com.valerochka1337.valerochkagym.data.google.WorkoutImportRepository
import com.valerochka1337.valerochkagym.data.google.WorkoutImportRepositoryImpl
```

```kotlin
    @Binds
    @Singleton
    abstract fun bindWorkoutImportRepository(impl: WorkoutImportRepositoryImpl): WorkoutImportRepository
```

- [ ] **Step 2: Проверить сборку DI-графа**

Run: `./gradlew :app:kspDebugKotlin`
Expected: BUILD SUCCESSFUL (Hilt находит `GymDatabase`, `SheetsApi`, DAO — все уже в графе).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/valerochka1337/valerochkagym/di/GoogleModule.kt
git commit -m "feat: DI-биндинг WorkoutImportRepository"
```

---

### Task 9: Запуск импорта из настроек

**Files:**
- Modify: `app/src/main/java/com/valerochka1337/valerochkagym/ui/settings/SettingsViewModel.kt`
- Test: `app/src/test/java/com/valerochka1337/valerochkagym/ui/SettingsViewModelTest.kt`

- [ ] **Step 1: Написать падающие тесты**

В `SettingsViewModelTest.kt`:

Добавить импорты:

```kotlin
import com.valerochka1337.valerochkagym.data.google.ImportResult
import com.valerochka1337.valerochkagym.data.google.WorkoutImportRepository
import kotlinx.coroutines.flow.first
```

Добавить фейк рядом с `FakeUploadScheduler`:

```kotlin
    /** [WorkoutImportRepository] с программируемым результатом и счётчиком вызовов. */
    private class FakeImportRepository(
        private val result: ImportResult = ImportResult.Success(3),
    ) : WorkoutImportRepository {
        var calls: Int = 0
            private set
        override suspend fun importAll(): ImportResult {
            calls++
            return result
        }
    }
```

Обновить все конструкторы `SettingsViewModel(...)` в существующих тестах: добавить четвёртым аргументом `FakeImportRepository()`. Например строка 48 становится:

```kotlin
        val viewModel = SettingsViewModel(settingsRepository(defaultRestSeconds = 120), FakeGoogleAuth(), FakeUploadScheduler(), FakeImportRepository())
```

(Аналогично для всех остальных вхождений `SettingsViewModel(` в файле.)

Добавить новый регион с тестами:

```kotlin
    // region import on link save

    @Test
    fun `saving a valid link triggers import and posts the result message`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val import = FakeImportRepository(ImportResult.Success(3))
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), import)
            collectUiState(viewModel)

            viewModel.setSpreadsheetInput(validSpreadsheetId)

            assertEquals(1, import.calls)
            assertEquals("Импортировано тренировок: 3", viewModel.messages.first())
        }

    @Test
    fun `invalid link does not trigger import`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val import = FakeImportRepository()
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), import)
            collectUiState(viewModel)

            viewModel.setSpreadsheetInput("не ссылка")

            assertEquals(0, import.calls)
        }

    @Test
    fun `nothing to import posts an informational message`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val import = FakeImportRepository(ImportResult.NothingToImport)
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), import)
            collectUiState(viewModel)

            viewModel.setSpreadsheetInput(validSpreadsheetId)

            assertEquals("Нечего импортировать", viewModel.messages.first())
        }

    @Test
    fun `import failure posts the reason`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val import = FakeImportRepository(ImportResult.Failure("Нет доступа к таблице — проверьте вход и права"))
            val viewModel = SettingsViewModel(settingsRepository(), FakeGoogleAuth(), FakeUploadScheduler(), import)
            collectUiState(viewModel)

            viewModel.setSpreadsheetInput(validSpreadsheetId)

            assertEquals("Нет доступа к таблице — проверьте вход и права", viewModel.messages.first())
        }

    // endregion
```

- [ ] **Step 2: Запустить — убедиться, что не компилируется**

Run: `./gradlew :app:testDebugUnitTest --tests "*SettingsViewModelTest*"`
Expected: FAIL — конструктор `SettingsViewModel` не принимает 4-й аргумент.

- [ ] **Step 3: Внедрить импорт в ViewModel**

В `SettingsViewModel.kt`:

Добавить импорты:

```kotlin
import com.valerochka1337.valerochkagym.data.google.ImportResult
import com.valerochka1337.valerochkagym.data.google.WorkoutImportRepository
```

Добавить зависимость в конструктор:

```kotlin
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val googleAuth: GoogleAuth,
    private val uploadScheduler: UploadScheduler,
    private val importRepository: WorkoutImportRepository,
) : ViewModel() {
```

Заменить `setSpreadsheetInput` так, чтобы после сохранения ссылки запускался импорт:

```kotlin
    /** Сохраняет ID таблицы, распарсив ссылку или голый ID; при неудаче выставляет ошибку. */
    fun setSpreadsheetInput(raw: String) {
        val id = spreadsheetIdFrom(raw)
        if (id == null) {
            spreadsheetError.value = true
            return
        }
        spreadsheetError.value = false
        viewModelScope.launch {
            settingsRepository.setSpreadsheetId(id)
            importHistory()
        }
    }

    /** Разово тянет историю из только что сохранённой таблицы и уведомляет о результате. */
    private suspend fun importHistory() {
        val message = when (val result = importRepository.importAll()) {
            is ImportResult.Success -> "Импортировано тренировок: ${result.imported}"
            ImportResult.NothingToImport -> "Нечего импортировать"
            is ImportResult.Failure -> result.reason
        }
        _messages.send(message)
    }
```

- [ ] **Step 4: Запустить тесты**

Run: `./gradlew :app:testDebugUnitTest --tests "*SettingsViewModelTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valerochka1337/valerochkagym/ui/settings/SettingsViewModel.kt \
        app/src/test/java/com/valerochka1337/valerochkagym/ui/SettingsViewModelTest.kt
git commit -m "feat: импорт истории при сохранении ссылки на таблицу"
```

---

### Task 10: Финальная проверка

- [ ] **Step 1: Полный прогон тестов**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, все тесты зелёные.

- [ ] **Step 2: Сборка приложения**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Ручная проверка сценария (smoke)**

На устройстве/эмуляторе: войти в Google в настройках → вставить ссылку на таблицу с историей (лист `Workouts`) → снэкбар «Импортировано тренировок: N» → раздел «История» показывает эти тренировки; длительность ненулевая (finishedAt из времени подходов). Повторная вставка той же ссылки → «Нечего импортировать», дублей нет.

---

## Заметки для исполнителя

- Команды `./gradlew ...` — стандартные для Android; если в среде есть обёртка, используйте её. Одиночный тест-класс фильтруется `--tests "*ClassName*"`.
- Интерфейс `ActiveWorkoutRepository.toggleSetCompleted(setId, completed)` НЕ меняется — все фейки в существующих тестах остаются валидны.
- Числа из Sheets читаются как строки (`ValueRangeDto.values: List<List<String>>?`); парсер допускает запятую-разделитель. Если в будущем понадобится строгая типизация — добавить `valueRenderOption=UNFORMATTED_VALUE` и отдельный DTO, но для v1 это YAGNI.
- Схемы Room (`app/schemas/.../2.json`) коммитятся вместе с кодом (Task 1).
