package com.valerochka1337.valerochkagym.di

import com.valerochka1337.valerochkagym.data.google.AccountBoundGoogleAuth
import com.valerochka1337.valerochkagym.data.google.CalendarRepository
import com.valerochka1337.valerochkagym.data.google.CalendarRepositoryImpl
import com.valerochka1337.valerochkagym.data.google.ConfigurationSheetsRepository
import com.valerochka1337.valerochkagym.data.google.ConfigurationSheetsRepositoryImpl
import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.GoogleAuthManager
import com.valerochka1337.valerochkagym.data.google.SheetsRepository
import com.valerochka1337.valerochkagym.data.google.SheetsRepositoryImpl
import com.valerochka1337.valerochkagym.data.google.WorkoutImportRepository
import com.valerochka1337.valerochkagym.data.google.WorkoutImportRepositoryImpl
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleRepository
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleRepositoryImpl
import com.valerochka1337.valerochkagym.worker.WeeklyScheduleRecoveryScheduler
import com.valerochka1337.valerochkagym.worker.WorkManagerWeeklyScheduleRecoveryScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
abstract class GoogleModule {

  @Binds @Singleton abstract fun bindGoogleAuth(impl: GoogleAuthManager): GoogleAuth

  @Binds
  @Singleton
  abstract fun bindAccountBoundGoogleAuth(impl: GoogleAuthManager): AccountBoundGoogleAuth

  @Binds @Singleton abstract fun bindSheetsRepository(impl: SheetsRepositoryImpl): SheetsRepository

  @Binds
  @Singleton
  abstract fun bindConfigurationSheetsRepository(
      impl: ConfigurationSheetsRepositoryImpl,
  ): ConfigurationSheetsRepository

  @Binds
  @Singleton
  abstract fun bindCalendarRepository(impl: CalendarRepositoryImpl): CalendarRepository

  @Binds
  @Singleton
  abstract fun bindWorkoutImportRepository(
      impl: WorkoutImportRepositoryImpl
  ): WorkoutImportRepository

  @Binds
  @Singleton
  abstract fun bindWeeklyScheduleRepository(
      impl: WeeklyScheduleRepositoryImpl
  ): WeeklyScheduleRepository

  @Binds
  @Singleton
  abstract fun bindWeeklyScheduleRecoveryScheduler(
      impl: WorkManagerWeeklyScheduleRecoveryScheduler,
  ): WeeklyScheduleRecoveryScheduler
}
