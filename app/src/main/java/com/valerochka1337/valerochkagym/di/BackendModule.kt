package com.valerochka1337.valerochkagym.di

import com.valerochka1337.valerochkagym.data.backend.*
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class BackendModule {
  @Binds abstract fun transport(impl: BackendApi): BackendTransport

  @Binds abstract fun sessions(impl: BackendTokenStore): BackendSessionStore

  @Binds abstract fun calendarCloudStatus(impl: BackendSync): CalendarCloudStatus

  @Binds abstract fun syncReadySource(impl: SyncReadyAdapter): SyncReadySource
}
