package com.valerochka1337.valerochkagym.di

import com.valerochka1337.valerochkagym.data.google.CalendarRepository
import com.valerochka1337.valerochkagym.data.google.CalendarRepositoryImpl
import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.GoogleAuthManager
import com.valerochka1337.valerochkagym.data.google.SheetsRepository
import com.valerochka1337.valerochkagym.data.google.SheetsRepositoryImpl
import com.valerochka1337.valerochkagym.data.google.WorkoutImportRepository
import com.valerochka1337.valerochkagym.data.google.WorkoutImportRepositoryImpl
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleRepository
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class GoogleModule {

    @Binds
    @Singleton
    abstract fun bindGoogleAuth(impl: GoogleAuthManager): GoogleAuth

    @Binds
    @Singleton
    abstract fun bindSheetsRepository(impl: SheetsRepositoryImpl): SheetsRepository

    @Binds
    @Singleton
    abstract fun bindCalendarRepository(impl: CalendarRepositoryImpl): CalendarRepository

    @Binds
    @Singleton
    abstract fun bindWorkoutImportRepository(impl: WorkoutImportRepositoryImpl): WorkoutImportRepository

    @Binds
    @Singleton
    abstract fun bindWeeklyScheduleRepository(impl: WeeklyScheduleRepositoryImpl): WeeklyScheduleRepository
}
