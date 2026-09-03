package com.valerochka1337.valerochkagym.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.work.WorkManager
import com.valerochka1337.valerochkagym.data.backup.DatabaseExporter
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.GymDatabaseCallback
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.dao.ConfigurationTombstoneDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.dao.GymDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.ScheduledWorkoutDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.service.WallClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
private val Context.aiApiSecretsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ai_secrets",
)
private val Context.weeklyScheduleOperationsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "weekly_schedule_operations",
)

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @ComputeDispatcher
    fun provideComputeDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * Стенные часы для [com.valerochka1337.valerochkagym.service.RestTimerEngine]: дедлайн отдыха
     * уезжает в `Notification.setWhen`, а хронометр уведомления сравнивает его именно с
     * [System.currentTimeMillis].
     */
    @Provides
    @Singleton
    fun provideWallClock(): WallClock = WallClock { System.currentTimeMillis() }

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        callback: GymDatabaseCallback,
    ): GymDatabase =
        Room.databaseBuilder(context, GymDatabase::class.java, DatabaseExporter.DATABASE_NAME)
            .addCallback(callback)
            .addMigrations(
                GymDatabase.MIGRATION_1_2,
                GymDatabase.MIGRATION_2_3,
                GymDatabase.MIGRATION_3_4,
                GymDatabase.MIGRATION_4_5,
                GymDatabase.MIGRATION_5_6,
                GymDatabase.MIGRATION_6_7,
                GymDatabase.MIGRATION_7_8,
                GymDatabase.MIGRATION_8_9,
                GymDatabase.MIGRATION_9_10,
            )
            .build()

    @Provides
    fun provideBodyMeasurementDao(database: GymDatabase): BodyMeasurementDao = database.bodyMeasurementDao()

    @Provides
    fun provideConfigurationTombstoneDao(database: GymDatabase): ConfigurationTombstoneDao =
        database.configurationTombstoneDao()

    @Provides
    fun provideExerciseDao(database: GymDatabase): ExerciseDao = database.exerciseDao()

    @Provides
    fun provideExerciseMuscleDao(database: GymDatabase): ExerciseMuscleDao =
        database.exerciseMuscleDao()

    @Provides
    fun provideGymDao(database: GymDatabase): GymDao = database.gymDao()

    @Provides
    fun provideRoutineDao(database: GymDatabase): RoutineDao = database.routineDao()

    @Provides
    fun provideWorkoutDao(database: GymDatabase): WorkoutDao = database.workoutDao()

    @Provides
    fun provideScheduledWorkoutDao(database: GymDatabase): ScheduledWorkoutDao =
        database.scheduledWorkoutDao()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore

    @Provides
    @Singleton
    @AiApiSecrets
    fun provideAiApiSecretsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.aiApiSecretsDataStore

    @Provides
    @Singleton
    @WeeklyScheduleOperations
    fun provideWeeklyScheduleOperationsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.weeklyScheduleOperationsDataStore

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
