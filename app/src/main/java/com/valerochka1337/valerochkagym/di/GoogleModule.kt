package com.valerochka1337.valerochkagym.di

import com.valerochka1337.valerochkagym.data.google.GoogleAuth
import com.valerochka1337.valerochkagym.data.google.GoogleAuthManager
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
}
