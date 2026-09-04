package com.valerochka1337.valerochkagym.data.update

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** Передаёт callbacks системной install-сессии в живой update-сценарий приложения. */
@Singleton
class AppUpdateInstallEventBus @Inject constructor() {
  private val channel = Channel<AppUpdateInstallEvent>(Channel.BUFFERED)

  val events: Flow<AppUpdateInstallEvent> = channel.receiveAsFlow()

  internal fun publish(event: AppUpdateInstallEvent) {
    channel.trySend(event)
  }
}
