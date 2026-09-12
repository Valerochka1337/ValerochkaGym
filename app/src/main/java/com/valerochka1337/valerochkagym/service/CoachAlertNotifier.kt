package com.valerochka1337.valerochkagym.service

import android.app.NotificationManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Accessed on Main by Activity, chat and the workout service. */
@Singleton
class CoachAlertNotifier @Inject constructor(@ApplicationContext private val context: Context) {
  private val visibleActivities = mutableSetOf<Any>()
  private val manager
    get() = context.getSystemService(NotificationManager::class.java)

  fun activityStarted(activity: Any) {
    visibleActivities.add(activity)
  }

  fun activityStopped(activity: Any) {
    visibleActivities.remove(activity)
  }

  fun show(workoutId: String) {
    if (visibleActivities.isNotEmpty()) return
    CoachAlertNotificationFactory.createChannel(manager)
    manager.notify(
        workoutId,
        CoachAlertNotificationFactory.NOTIFICATION_ID,
        CoachAlertNotificationFactory.build(context, workoutId),
    )
  }

  fun chatViewed(workoutId: String) {
    manager.cancel(workoutId, CoachAlertNotificationFactory.NOTIFICATION_ID)
    // Remove an alert posted by a build that predates workout-specific notification tags.
    manager.cancel(CoachAlertNotificationFactory.NOTIFICATION_ID)
  }
}
