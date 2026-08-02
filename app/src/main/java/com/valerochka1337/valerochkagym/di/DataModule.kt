package com.valerochka1337.valerochkagym.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.work.WorkManager
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import com.valerochka1337.valerochkagym.data.db.GymDatabaseCallback
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.ScheduledWorkoutDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.service.WallClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        Room.databaseBuilder(context, GymDatabase::class.java, "gym.db")
            .addCallback(callback)
            .addMigrations(GymDatabase.MIGRATION_1_2, GymDatabase.MIGRATION_2_3)
            .build()

    @Provides
    fun provideExerciseDao(database: GymDatabase): ExerciseDao = database.exerciseDao()

    @Provides
    fun provideExerciseMuscleDao(database: GymDatabase): ExerciseMuscleDao =
        database.exerciseMuscleDao()

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
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
