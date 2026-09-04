package com.valerochka1337.valerochkagym.di

import com.valerochka1337.valerochkagym.data.update.AndroidAppUpdateInstaller
import com.valerochka1337.valerochkagym.data.update.AndroidPostUpdateNotificationPublisher
import com.valerochka1337.valerochkagym.data.update.AppUpdateInstaller
import com.valerochka1337.valerochkagym.data.update.AppUpdateRepository
import com.valerochka1337.valerochkagym.data.update.GitHubAppUpdateRepository
import com.valerochka1337.valerochkagym.data.update.PostUpdateNotificationPublisher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
abstract class UpdateModule {

  @Binds
  abstract fun bindAppUpdateRepository(
      implementation: GitHubAppUpdateRepository
  ): AppUpdateRepository

  @Binds
  abstract fun bindAppUpdateInstaller(implementation: AndroidAppUpdateInstaller): AppUpdateInstaller

  @Binds
  abstract fun bindPostUpdateNotificationPublisher(
      implementation: AndroidPostUpdateNotificationPublisher,
  ): PostUpdateNotificationPublisher
}
