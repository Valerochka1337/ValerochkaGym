package com.valerochka1337.valerochkagym.di

import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.GoogleAuthManager
import com.valerochka1337.valerochkagym.data.google.SheetsRepository
import com.valerochka1337.valerochkagym.data.google.SheetsRepositoryImpl
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
}
