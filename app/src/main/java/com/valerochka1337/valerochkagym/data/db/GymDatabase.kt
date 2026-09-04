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
import com.valerochka1337.valerochkagym.data.db.dao.MuscleLoadUpgradeNoticeDao
import com.valerochka1337.valerochkagym.data.db.dao.GymDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.ScheduledWorkoutDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.ConfigurationTombstoneEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoadUpgradeNoticeEntity
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
        MuscleLoadUpgradeNoticeEntity::class,
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
    version = 13,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class GymDatabase : RoomDatabase() {
    abstract fun bodyMeasurementDao(): BodyMeasurementDao
    abstract fun configurationTombstoneDao(): ConfigurationTombstoneDao
    abstract fun exerciseDao(): ExerciseDao
    abstract fun exerciseMuscleDao(): ExerciseMuscleDao
    abstract fun muscleLoadUpgradeNoticeDao(): MuscleLoadUpgradeNoticeDao
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

        /** v8 → v9: retained shipping migration for devices upgrading through v10. */
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `exercise_variants` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `syncId` TEXT NOT NULL, " +
                        "`exerciseId` INTEGER NOT NULL, `name` TEXT NOT NULL, `normalizedName` TEXT NOT NULL, " +
                        "`isArchived` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_exercise_variants_syncId` ON `exercise_variants` (`syncId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_exercise_variants_exerciseId_syncId` ON `exercise_variants` (`exerciseId`, `syncId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_exercise_variants_exerciseId_normalizedName` ON `exercise_variants` (`exerciseId`, `normalizedName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_exercise_variants_exerciseId_isArchived` ON `exercise_variants` (`exerciseId`, `isArchived`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `routine_exercises_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `routineId` INTEGER NOT NULL, " +
                        "`exerciseId` INTEGER NOT NULL, `variantSyncId` TEXT, `position` INTEGER NOT NULL, " +
                        "`restSeconds` INTEGER, `plannedSetsJson` TEXT NOT NULL, " +
                        "FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION, " +
                        "FOREIGN KEY(`exerciseId`, `variantSyncId`) REFERENCES `exercise_variants`(`exerciseId`, `syncId`) ON UPDATE NO ACTION ON DELETE NO ACTION)",
                )
                db.execSQL(
                    "INSERT INTO `routine_exercises_new` (`id`,`routineId`,`exerciseId`,`variantSyncId`,`position`,`restSeconds`,`plannedSetsJson`) " +
                        "SELECT `id`,`routineId`,`exerciseId`,NULL,`position`,`restSeconds`,`plannedSetsJson` FROM `routine_exercises`",
                )
                db.execSQL("DROP TABLE `routine_exercises`")
                db.execSQL("ALTER TABLE `routine_exercises_new` RENAME TO `routine_exercises`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_routine_exercises_routineId` ON `routine_exercises` (`routineId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_routine_exercises_exerciseId` ON `routine_exercises` (`exerciseId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_routine_exercises_exerciseId_variantSyncId` ON `routine_exercises` (`exerciseId`, `variantSyncId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workout_exercises_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workoutId` TEXT NOT NULL, " +
                        "`exerciseId` INTEGER NOT NULL, `sectionId` TEXT NOT NULL, `variantSyncId` TEXT, " +
                        "`variantNameSnapshot` TEXT, `position` INTEGER NOT NULL, CHECK(length(trim(`sectionId`)) > 0), " +
                        "CHECK((`variantSyncId` IS NULL AND `variantNameSnapshot` IS NULL) OR " +
                        "(`variantSyncId` IS NOT NULL AND length(trim(`variantNameSnapshot`)) > 0)), " +
                        "FOREIGN KEY(`workoutId`) REFERENCES `workouts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)",
                )
                db.execSQL("CREATE TABLE `workout_sets_v8_backup` AS SELECT * FROM `workout_sets`")
                db.query("SELECT `id`, `workoutId`, `exerciseId`, `position` FROM `workout_exercises`").use { cursor ->
                    while (cursor.moveToNext()) {
                        db.execSQL(
                            "INSERT INTO `workout_exercises_new` (`id`,`workoutId`,`exerciseId`,`sectionId`,`variantSyncId`,`variantNameSnapshot`,`position`) VALUES (?,?,?,?,?,?,?)",
                            arrayOf<Any?>(cursor.getLong(0), cursor.getString(1), cursor.getLong(2), UUID.randomUUID().toString(), null, null, cursor.getInt(3)),
                        )
                    }
                }
                db.execSQL("DROP TABLE `workout_exercises`")
                db.execSQL("ALTER TABLE `workout_exercises_new` RENAME TO `workout_exercises`")
                db.execSQL("DELETE FROM `workout_sets`")
                db.execSQL("INSERT INTO `workout_sets` SELECT * FROM `workout_sets_v8_backup`")
                db.execSQL("DROP TABLE `workout_sets_v8_backup`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workout_exercises_workoutId` ON `workout_exercises` (`workoutId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workout_exercises_exerciseId` ON `workout_exercises` (`exerciseId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workout_exercises_sectionId` ON `workout_exercises` (`sectionId`)")
            }
        }

        /** v9 → v10: discard variant metadata while preserving every base row and workout set. */
        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.beginTransaction()
                try {
                    db.execSQL(
                        "CREATE TABLE `routine_exercises_new` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `routineId` INTEGER NOT NULL, " +
                            "`exerciseId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `restSeconds` INTEGER, " +
                            "`plannedSetsJson` TEXT NOT NULL, " +
                            "FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                            "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)",
                    )
                    db.execSQL(
                        "INSERT INTO `routine_exercises_new` (`id`,`routineId`,`exerciseId`,`position`,`restSeconds`,`plannedSetsJson`) " +
                            "SELECT `id`,`routineId`,`exerciseId`,`position`,`restSeconds`,`plannedSetsJson` FROM `routine_exercises`",
                    )
                    db.execSQL("DROP TABLE `routine_exercises`")
                    db.execSQL("ALTER TABLE `routine_exercises_new` RENAME TO `routine_exercises`")
                    db.execSQL("CREATE INDEX `index_routine_exercises_routineId` ON `routine_exercises` (`routineId`)")
                    db.execSQL("CREATE INDEX `index_routine_exercises_exerciseId` ON `routine_exercises` (`exerciseId`)")

                    db.execSQL("CREATE TABLE `workout_sets_v9_backup` AS SELECT * FROM `workout_sets`")
                    db.execSQL(
                        "CREATE TABLE `workout_exercises_new` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workoutId` TEXT NOT NULL, " +
                            "`exerciseId` INTEGER NOT NULL, `sectionId` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                            "CHECK(length(trim(`sectionId`)) > 0), " +
                            "FOREIGN KEY(`workoutId`) REFERENCES `workouts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                            "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)",
                    )
                    db.execSQL(
                        "INSERT INTO `workout_exercises_new` (`id`,`workoutId`,`exerciseId`,`sectionId`,`position`) " +
                            "SELECT `id`,`workoutId`,`exerciseId`,`sectionId`,`position` FROM `workout_exercises`",
                    )
                    db.execSQL("DROP TABLE `workout_exercises`")
                    db.execSQL("ALTER TABLE `workout_exercises_new` RENAME TO `workout_exercises`")
                    db.execSQL("DELETE FROM `workout_sets`")
                    db.execSQL("INSERT INTO `workout_sets` SELECT * FROM `workout_sets_v9_backup`")
                    db.execSQL("DROP TABLE `workout_sets_v9_backup`")
                    db.execSQL("CREATE INDEX `index_workout_exercises_workoutId` ON `workout_exercises` (`workoutId`)")
                    db.execSQL("CREATE INDEX `index_workout_exercises_exerciseId` ON `workout_exercises` (`exerciseId`)")
                    db.execSQL("CREATE UNIQUE INDEX `index_workout_exercises_sectionId` ON `workout_exercises` (`sectionId`)")
                    db.execSQL("DROP TABLE `exercise_variants`")
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }

        /** v10 → v12: v10 уже имеет целевую base-only схему. */
        val MIGRATION_10_12: Migration = object : Migration(10, 12) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        /**
         * v11 → v12: recovery для выпущенной до v10 вариации, которая успела попасть на
         * устройства. Сначала удаляем дочерние мышцы, затем перестраиваем таблицы, сохраняем
         * подходы через backup и только после этого удаляем варианты.
         */
        val MIGRATION_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.beginTransaction()
                try {
                    db.execSQL("DROP TABLE `exercise_variant_muscles`")

                    db.execSQL(
                        "CREATE TABLE `routine_exercises_new` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `routineId` INTEGER NOT NULL, " +
                            "`exerciseId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `restSeconds` INTEGER, " +
                            "`plannedSetsJson` TEXT NOT NULL, " +
                            "FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                            "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)",
                    )
                    db.execSQL(
                        "INSERT INTO `routine_exercises_new` (`id`,`routineId`,`exerciseId`,`position`,`restSeconds`,`plannedSetsJson`) " +
                            "SELECT `id`,`routineId`,`exerciseId`,`position`,`restSeconds`,`plannedSetsJson` FROM `routine_exercises`",
                    )
                    db.execSQL("DROP TABLE `routine_exercises`")
                    db.execSQL("ALTER TABLE `routine_exercises_new` RENAME TO `routine_exercises`")
                    db.execSQL("CREATE INDEX `index_routine_exercises_routineId` ON `routine_exercises` (`routineId`)")
                    db.execSQL("CREATE INDEX `index_routine_exercises_exerciseId` ON `routine_exercises` (`exerciseId`)")

                    db.execSQL("CREATE TABLE `workout_sets_v11_backup` AS SELECT * FROM `workout_sets`")
                    db.execSQL(
                        "CREATE TABLE `workout_exercises_new` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workoutId` TEXT NOT NULL, " +
                            "`exerciseId` INTEGER NOT NULL, `sectionId` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                            "FOREIGN KEY(`workoutId`) REFERENCES `workouts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                            "FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)",
                    )
                    db.execSQL(
                        "INSERT INTO `workout_exercises_new` (`id`,`workoutId`,`exerciseId`,`sectionId`,`position`) " +
                            "SELECT `id`,`workoutId`,`exerciseId`,`sectionId`,`position` FROM `workout_exercises`",
                    )
                    db.execSQL("DROP TABLE `workout_exercises`")
                    db.execSQL("ALTER TABLE `workout_exercises_new` RENAME TO `workout_exercises`")
                    db.execSQL("DELETE FROM `workout_sets`")
                    db.execSQL(
                        "INSERT INTO `workout_sets` (`id`,`workoutExerciseId`,`setIndex`,`weightKg`,`reps`," +
                            "`durationSec`,`speedKmh`,`inclinePct`,`isCompleted`,`completedAt`) " +
                            "SELECT `id`,`workoutExerciseId`,`setIndex`,`weightKg`,`reps`,`durationSec`," +
                            "`speedKmh`,`inclinePct`,`isCompleted`,`completedAt` FROM `workout_sets_v11_backup`",
                    )
                    db.execSQL("DROP TABLE `workout_sets_v11_backup`")
                    db.execSQL("CREATE INDEX `index_workout_exercises_workoutId` ON `workout_exercises` (`workoutId`)")
                    db.execSQL("CREATE INDEX `index_workout_exercises_exerciseId` ON `workout_exercises` (`exerciseId`)")
                    db.execSQL("CREATE UNIQUE INDEX `index_workout_exercises_sectionId` ON `workout_exercises` (`sectionId`)")

                    db.execSQL("DROP TABLE `exercise_variants`")
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }

        /**
         * v12 → v13 changes percentage-like load values into the durable role encoding.
         * A legacy zero meant "not involved", so only this migration removes zero rows. From
         * v13 onward an explicit zero is a stabilizer and must remain distinct from absence.
         */
        val MIGRATION_12_13: Migration = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.beginTransaction()
                try {
                    // Recovery fixtures (and interrupted vendor restores) can carry the current
                    // column while their user_version is still older. The normal v12 schema does
                    // not, but guarding this DDL keeps the handwritten migration reopen-safe.
                    val hasReviewColumn = db.query("PRAGMA table_info(exercises)").use { columns ->
                        var found = false
                        while (columns.moveToNext()) if (columns.getString(1) == "needsMuscleMapReview") found = true
                        found
                    }
                    if (!hasReviewColumn) {
                        db.execSQL("ALTER TABLE exercises ADD COLUMN needsMuscleMapReview INTEGER NOT NULL DEFAULT 0")
                    }
                    db.execSQL("CREATE TABLE IF NOT EXISTS `muscle_load_upgrade_notice` (`id` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                    db.execSQL("INSERT OR IGNORE INTO muscle_load_upgrade_notice(id) VALUES(1)")
                    db.execSQL("DELETE FROM exercise_muscles WHERE contribution = 0")
                    db.execSQL(
                        "UPDATE exercise_muscles SET contribution = CASE " +
                            "WHEN contribution >= 60 THEN 100 " +
                            "WHEN contribution >= 25 THEN 50 ELSE 0 END",
                    )
                    // Keep the existing local row identity by moving old CHEST to upper chest.
                    db.execSQL("UPDATE exercise_muscles SET muscle = 'UPPER_CHEST' WHERE muscle = 'CHEST'")
                    // CHEST was approximate. Only custom maps are duplicated and flagged for review.
                    db.execSQL(
                        "INSERT INTO exercise_muscles(exerciseId, muscle, contribution) " +
                            "SELECT m.exerciseId, 'LOWER_CHEST', m.contribution " +
                            "FROM exercise_muscles m JOIN exercises e ON e.id = m.exerciseId " +
                            "WHERE m.muscle = 'UPPER_CHEST' AND e.isCustom = 1",
                    )
                    db.execSQL(
                        "UPDATE exercises SET needsMuscleMapReview = 1 WHERE isCustom = 1 AND id IN " +
                            "(SELECT exerciseId FROM exercise_muscles WHERE muscle IN ('UPPER_CHEST','LOWER_CHEST') " +
                            "GROUP BY exerciseId HAVING COUNT(*) = 2)",
                    )
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }

        /** Единственный production/test реестр всех поддерживаемых путей до текущей схемы. */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_12,
            MIGRATION_11_12,
            MIGRATION_12_13,
        )
    }
}
