package com.valerochka1337.valerochkagym.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.ScheduledWorkoutDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ScheduledWorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity

@Database(
    entities = [
        BodyMeasurementEntity::class,
        ExerciseEntity::class,
        ExerciseMuscleEntity::class,
        RoutineEntity::class,
        RoutineExerciseEntity::class,
        ScheduledWorkoutEntity::class,
        WorkoutEntity::class,
        WorkoutExerciseEntity::class,
        WorkoutSetEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class GymDatabase : RoomDatabase() {
    abstract fun bodyMeasurementDao(): BodyMeasurementDao
    abstract fun exerciseDao(): ExerciseDao
    abstract fun exerciseMuscleDao(): ExerciseMuscleDao
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
    }
}
