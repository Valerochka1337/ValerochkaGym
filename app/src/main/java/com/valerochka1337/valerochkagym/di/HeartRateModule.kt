package com.valerochka1337.valerochkagym.di

import com.valerochka1337.valerochkagym.service.heartrate.BleHeartRateMonitor
import com.valerochka1337.valerochkagym.service.heartrate.HeartRateMonitor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
abstract class HeartRateModule {

  @Binds @Singleton abstract fun bindHeartRateMonitor(impl: BleHeartRateMonitor): HeartRateMonitor
}
