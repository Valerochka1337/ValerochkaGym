package com.valerochka1337.valerochkagym.di

import com.valerochka1337.valerochkagym.data.update.AndroidAppUpdateInstaller
import com.valerochka1337.valerochkagym.data.update.AppUpdateInstaller
import com.valerochka1337.valerochkagym.data.update.AppUpdateRepository
import com.valerochka1337.valerochkagym.data.update.GitHubAppUpdateRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {

    @Binds
    abstract fun bindAppUpdateRepository(implementation: GitHubAppUpdateRepository): AppUpdateRepository

    @Binds
    abstract fun bindAppUpdateInstaller(implementation: AndroidAppUpdateInstaller): AppUpdateInstaller
}

