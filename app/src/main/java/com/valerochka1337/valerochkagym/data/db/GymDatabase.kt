package com.valerochka1337.valerochkagym.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.dao.ConfigurationTombstoneDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.dao.GymDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.ScheduledWorkoutDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.ConfigurationTombstoneEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.GymEntity
import com.valerochka1337.valerochkagym.data.db.entity.GymExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineGymEntity
import com.valerochka1337.valerochkagym.data.db.entity.ScheduledWorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutGymEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.entity.builtInExerciseSyncId
import com.valerochka1337.valerochkagym.data.db.entity.migratedCustomExerciseSyncId
import java.util.UUID

@Database(
    entities = [
        BodyMeasurementEntity::class,
        ConfigurationTombstoneEntity::class,
        ExerciseEntity::class,
        ExerciseMuscleEntity::class,
        GymEntity::class,
        GymExerciseEntity::class,
        RoutineEntity::class,
        RoutineExerciseEntity::class,
        RoutineGymEntity::class,
        ScheduledWorkoutEntity::class,
        WorkoutEntity::class,
        WorkoutExerciseEntity::class,
        WorkoutGymEntity::class,
        WorkoutSetEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class GymDatabase : RoomDatabase() {
    abstract fun bodyMeasurementDao(): BodyMeasurementDao
    abstract fun configurationTombstoneDao(): ConfigurationTombstoneDao
    abstract fun exerciseDao(): ExerciseDao
    abstract fun exerciseMuscleDao(): ExerciseMuscleDao
    abstract fun gymDao(): GymDao
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

        /**
         * v2 → v3: появляется карта вовлечения мышц `exercise_muscles` ([ExerciseMuscleEntity]).
         * Таблица создаётся пустой — заполняет её [ExerciseMuscleSeeder] при открытии базы
         * (по каталогу для встроенных упражнений, по группе мышц для своих и импортированных),
         * так что миграция не тащит на себе каталог и остаётся чистым DDL.
         *
         * DDL повторяет то, что генерирует Room для v3 (см. `schemas/3.json`) — при расхождении
         * Room упадёт на проверке схемы при открытии; это ловит `Migration2To3Test`.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `exercise_muscles` (" +
                        "`exerciseId` INTEGER NOT NULL, `muscle` TEXT NOT NULL, " +
                        "`contribution` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`exerciseId`, `muscle`), " +
                        "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_exercise_muscles_exerciseId` " +
                        "ON `exercise_muscles` (`exerciseId`)",
                )
            }
        }

        /**
         * v3 → v4: отдельная таблица замеров тела. Пустые показатели хранятся как NULL, а не
         * как нули: это сохраняет честные разрывы в трендах и при экспорте в Sheets.
         *
         * DDL повторяет `schemas/.../4.json`; миграционный тест открывает получившуюся базу
         * через Room и тем самым проверяет типы, первичный ключ и оба индекса.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `body_measurements` (" +
                        "`id` TEXT NOT NULL, `measuredAt` INTEGER NOT NULL, " +
                        "`weightKg` REAL, `skeletalMuscleMassKg` REAL, " +
                        "`bodyFatPercentage` REAL, `visceralFatLevel` INTEGER, " +
                        "`waistHipRatio` REAL, `waistCm` REAL, `chestCm` REAL, " +
                        "`hipsCm` REAL, `rightRelaxedArmCm` REAL, `rightThighCm` REAL, " +
                        "`uploadStatus` TEXT NOT NULL, `uploadError` TEXT, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_body_measurements_measuredAt` " +
                        "ON `body_measurements` (`measuredAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_body_measurements_uploadStatus` " +
                        "ON `body_measurements` (`uploadStatus`)",
                )
            }
        }

        /**
         * v4 → v5: полный отчёт InBody. Все дополнительные значения nullable: старые ручные
         * замеры остаются валидными, а отсутствующая строка отчёта не маскируется нулём.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "ALTER TABLE body_measurements ADD COLUMN bodyFatMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN inBodyScore INTEGER",
                    "ALTER TABLE body_measurements ADD COLUMN totalBodyWaterLiters REAL",
                    "ALTER TABLE body_measurements ADD COLUMN proteinKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN mineralsKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN bodyMassIndex REAL",
                    "ALTER TABLE body_measurements ADD COLUMN fatFreeMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN basalMetabolicRateKcal INTEGER",
                    "ALTER TABLE body_measurements ADD COLUMN recommendedCalorieIntakeKcal INTEGER",
                    "ALTER TABLE body_measurements ADD COLUMN leftArmLeanMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN leftArmLeanPercentage REAL",
                    "ALTER TABLE body_measurements ADD COLUMN rightArmLeanMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN rightArmLeanPercentage REAL",
                    "ALTER TABLE body_measurements ADD COLUMN trunkLeanMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN trunkLeanPercentage REAL",
                    "ALTER TABLE body_measurements ADD COLUMN leftLegLeanMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN leftLegLeanPercentage REAL",
                    "ALTER TABLE body_measurements ADD COLUMN rightLegLeanMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN rightLegLeanPercentage REAL",
                    "ALTER TABLE body_measurements ADD COLUMN leftArmFatMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN leftArmFatPercentage REAL",
                    "ALTER TABLE body_measurements ADD COLUMN rightArmFatMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN rightArmFatPercentage REAL",
                    "ALTER TABLE body_measurements ADD COLUMN trunkFatMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN trunkFatPercentage REAL",
                    "ALTER TABLE body_measurements ADD COLUMN leftLegFatMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN leftLegFatPercentage REAL",
                    "ALTER TABLE body_measurements ADD COLUMN rightLegFatMassKg REAL",
                    "ALTER TABLE body_measurements ADD COLUMN rightLegFatPercentage REAL",
                ).forEach(db::execSQL)
            }
        }

        /**
         * v5 → v6: встроенные карты мышц переходят с локальной на общую шкалу нагрузки.
         * Удаляем только разметку стандартных упражнений: [GymDatabaseCallback] заполнит её заново из
         * [seedExerciseMuscles] при первом открытии. Свои упражнения и их ручная разметка сохраняются.
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "DELETE FROM exercise_muscles WHERE exerciseId IN " +
                        "(SELECT id FROM exercises WHERE isCustom = 0)",
                )
            }
        }

        /**
         * v6 → v7: у программ появляется независимый от локального ID ключ синхронизации и
         * монотонная версия снимка. UUID генерируются один раз именно в миграции, поэтому уже
         * созданные программы не меняют свою cloud-идентичность при следующем открытии базы.
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routines ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE routines ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                val migratedAt = System.currentTimeMillis()
                db.query("SELECT id FROM routines").use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow("id")
                    while (cursor.moveToNext()) {
                        db.execSQL(
                            "UPDATE routines SET syncId = ?, updatedAt = ? WHERE id = ?",
                            arrayOf<Any?>(UUID.randomUUID().toString(), migratedAt, cursor.getLong(idColumn)),
                        )
                    }
                }
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_routines_syncId ON routines (syncId)",
                )
            }
        }

        /**
         * v7 → v8: упражнения получают переносимую cloud-идентичность, а конфигурации залов —
         * нормализованные таблицы многие-ко-многим. У встроенного каталога UUID зависит только от
         * канонического имени и совпадает со fresh seed; у старых custom-записей дополнительно
         * участвует local ID, чтобы одноимённые пользовательские упражнения не схлопнулись.
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercises ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE exercises ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                val migratedAt = System.currentTimeMillis()
                db.query("SELECT id, name, isCustom FROM exercises").use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow("id")
                    val nameColumn = cursor.getColumnIndexOrThrow("name")
                    val customColumn = cursor.getColumnIndexOrThrow("isCustom")
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val name = cursor.getString(nameColumn)
                        val isCustom = cursor.getInt(customColumn) != 0
                        val syncId = if (!isCustom) {
                            builtInExerciseSyncId(name)
                        } else {
                            migratedCustomExerciseSyncId(id, name)
                        }
                        val updatedAt = if (isCustom) migratedAt else 1L
                        db.execSQL(
                            "UPDATE exercises SET syncId = ?, updatedAt = ? WHERE id = ?",
                            arrayOf<Any?>(syncId, updatedAt, id),
                        )
                    }
                }
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_exercises_syncId ON exercises (syncId)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `gyms` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`syncId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `name` TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_gyms_syncId` ON `gyms` (`syncId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `gym_exercises` (" +
                        "`gymId` INTEGER NOT NULL, `exerciseId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`gymId`, `exerciseId`), " +
                        "FOREIGN KEY(`gymId`) REFERENCES `gyms`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_gym_exercises_exerciseId` " +
                        "ON `gym_exercises` (`exerciseId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `routine_gyms` (" +
                        "`routineId` INTEGER NOT NULL, `gymId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`routineId`, `gymId`), " +
                        "FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`gymId`) REFERENCES `gyms`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE NO ACTION)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_routine_gyms_gymId` ON `routine_gyms` (`gymId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workout_gyms` (" +
                        "`workoutId` TEXT NOT NULL, `gymId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`workoutId`, `gymId`), " +
                        "FOREIGN KEY(`workoutId`) REFERENCES `workouts`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`gymId`) REFERENCES `gyms`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_workout_gyms_gymId` ON `workout_gyms` (`gymId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `configuration_tombstones` (" +
                        "`kind` TEXT NOT NULL, `syncId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`kind`, `syncId`))",
                )
            }
        }
    }
}
