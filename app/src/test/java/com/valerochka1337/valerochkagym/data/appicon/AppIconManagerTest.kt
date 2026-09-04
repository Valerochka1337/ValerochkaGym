package com.valerochka1337.valerochkagym.data.appicon

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.ui.theme.AccentColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [AppIconManager]: переключение alias-иконок отложено до ухода в фон, а до первого
 * показа экрана не происходит вовсе (старт процесса неотличим от фона). Активности поднимаются
 * Robolectric-ом, так что [android.app.Application.ActivityLifecycleCallbacks] получают настоящие
 * события start/stop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AppIconManagerTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun `an accent change before any screen is shown does not touch the aliases`() = runTest {
    val settings = repository()
    manager(settings, backgroundScope).startSync()

    settings.setAccent(AccentColor.LIME)

    assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, aliasState(AccentColor.LIME))
  }

  @Test
  fun `an accent change in the foreground applies only after the app goes to background`() =
      runTest {
        val settings = repository()
        manager(settings, backgroundScope).startSync()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()

        settings.setAccent(AccentColor.CYAN)
        // Пока экран виден — иконки не трогаем (иначе система снесёт задачу).
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, aliasState(AccentColor.CYAN))

        controller.pause().stop()

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, aliasState(AccentColor.CYAN))
        AccentColor.entries
            .filter { it != AccentColor.CYAN }
            .forEach { other ->
              assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, aliasState(other))
            }
      }

  @Test
  fun `an accent change in the background after the first foreground applies immediately`() =
      runTest {
        val settings = repository()
        manager(settings, backgroundScope).startSync()
        Robolectric.buildActivity(Activity::class.java).setup().pause().stop()

        settings.setAccent(AccentColor.CORAL)

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, aliasState(AccentColor.CORAL))
      }

  private fun kotlinx.coroutines.test.TestScope.manager(
      settings: SettingsRepository,
      scope: CoroutineScope,
  ): AppIconManager =
      AppIconManager(
          context = context,
          settingsRepository = settings,
          scope = CoroutineScope(scope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
      )

  private fun repository(): SettingsRepository = SettingsRepository(FakeDataStore())

  private fun aliasState(accent: AccentColor): Int =
      context.packageManager.getComponentEnabledSetting(
          ComponentName(context.packageName, "${context.packageName}.${accent.aliasName}"),
      )

  private class FakeDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences {
      state.value = transform(state.value)
      return state.value
    }
  }
}
