package com.valerochka1337.valerochkagym.di

import com.valerochka1337.valerochkagym.service.wear.WearableTransport
import com.valerochka1337.valerochkagym.service.wear.XiaomiWearTransport
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WearModule {

    @Binds
    @Singleton
    abstract fun bindWearableTransport(
        impl: XiaomiWearTransport,
    ): WearableTransport
}
