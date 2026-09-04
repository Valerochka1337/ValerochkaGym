package com.valerochka1337.valerochkagym.data.appicon

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.di.ApplicationScope
import com.valerochka1337.valerochkagym.ui.theme.AccentColor
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Держит иконку в лаунчере в согласии с выбранным акцентом.
 *
 * Android не умеет перекрашивать иконку на лету: в манифесте объявлены четыре `activity-alias` (по
 * одному на [AccentColor]), и «сменой иконки» называется включение нужного и выключение остальных.
 * Источник правды — настройка в DataStore, поэтому менеджер не вызывается из UI, а подписан на неё
 * ([startSync]): так иконка чинится и после переустановки, где состояние компонентов сбрасывается к
 * манифестному.
 *
 * Переключение отложено до ухода приложения в фон. Выключение alias'а, из которого запущена текущая
 * задача, система понимает как «компонента больше нет» и сносит задачу целиком: выбрал цвет —
 * вылетел на рабочий стол. В фоне та же операция стоит лишь пропажи карточки из «недавних».
 */
@Singleton
class AppIconManager
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

  private val lock = Any()

  /** Последний выбранный акцент; применяется, как только приложение окажется в фоне. */
  private var desired: AccentColor? = null

  private var startedActivities = 0

  /** До первого показа экрана «фон» неотличим от старта процесса, когда трогать alias'ы нельзя. */
  private var wasForeground = false

  /** Подписка на настройку акцента на всё время жизни процесса. */
  fun startSync() {
    (context as Application).registerActivityLifecycleCallbacks(ForegroundWatcher())
    scope.launch {
      settingsRepository.settings
          .map { it.accent }
          .distinctUntilChanged()
          .collect(::onAccentChanged)
    }
  }

  private fun onAccentChanged(accent: AccentColor) {
    val applyNow =
        synchronized(lock) {
          desired = accent
          wasForeground && startedActivities == 0
        }
    if (applyNow) apply(accent)
  }

  /**
   * Включает alias выбранного акцента и гасит остальные. Сначала включение, потом выключение: в
   * промежутке, когда не включён ни один launcher-компонент, лаунчер убирает приложение из списка и
   * может не вернуть его обратно.
   */
  private fun apply(accent: AccentColor) {
    val packageManager = context.packageManager
    setEnabled(packageManager, accent, enabled = true)
    AccentColor.entries
        .filter { it != accent }
        .forEach { setEnabled(packageManager, it, enabled = false) }
  }

  private fun setEnabled(packageManager: PackageManager, accent: AccentColor, enabled: Boolean) {
    val component = ComponentName(context.packageName, "${context.packageName}.${accent.aliasName}")
    val state =
        if (enabled) {
          PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
          PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
    // Лишний вызов заставляет лаунчер перерисовать список приложений, поэтому пишем только
    // при реальном расхождении. DONT_KILL_APP — чтобы смена акцента не убила процесс.
    if (packageManager.getComponentEnabledSetting(component) == state) return
    packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
  }

  /** Считает видимые экраны: ноль после того, как хотя бы один был показан, — приложение в фоне. */
  private inner class ForegroundWatcher : Application.ActivityLifecycleCallbacks {

    override fun onActivityStarted(activity: Activity) {
      synchronized(lock) {
        startedActivities++
        wasForeground = true
      }
    }

    override fun onActivityStopped(activity: Activity) {
      val pending =
          synchronized(lock) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            if (startedActivities == 0) desired else null
          }
      pending?.let(::apply)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
  }
}
