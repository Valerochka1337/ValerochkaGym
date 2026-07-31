package com.valerochka1337.valerochkagym.di

import com.valerochka1337.valerochkagym.data.ActiveWorkoutRepositoryImpl
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DomainModule {

    @Binds
    @Singleton
    abstract fun bindActiveWorkoutRepository(
        impl: ActiveWorkoutRepositoryImpl,
    ): ActiveWorkoutRepository
}
