package com.valerochka1337.valerochkagym

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.ui.haptics.LocalGymHaptics
import com.valerochka1337.valerochkagym.ui.haptics.rememberGymHaptics
import com.valerochka1337.valerochkagym.ui.navigation.MainScaffold
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    /**
     * Маршрут, на который просит перейти внешний запуск (уведомление тренировки). Поток, а не
     * простое поле: активность запускается в SINGLE_TOP, так что повторный тап приходит уже в
     * [onNewIntent] к живой композиции.
     */
    private val requestedRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readRequestedRoute(intent)
        setContent {
            val accentFlow = remember {
                settingsRepository.settings.map { it.accent }.distinctUntilChanged()
            }
            val hapticsFlow = remember {
                settingsRepository.settings.map { it.hapticsEnabled }.distinctUntilChanged()
            }
            // Пока акцент не прочитан из DataStore, не рисуем ничего: показать интерфейс дефолтным
            // цветом и перекрасить его через кадр заметнее, чем короткий тёмный фон окна.
            val accent by accentFlow.collectAsStateWithLifecycle(initialValue = null)
            val hapticsEnabled by hapticsFlow.collectAsStateWithLifecycle(initialValue = true)
            val route by requestedRoute.collectAsStateWithLifecycle()
            accent?.let {
                GymTheme(accent = it) {
                    CompositionLocalProvider(
                        LocalGymHaptics provides rememberGymHaptics(enabled = hapticsEnabled),
                    ) {
                        MainScaffold(
                            requestedRoute = route,
                            onRequestedRouteHandled = { requestedRoute.value = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readRequestedRoute(intent)
    }

    private fun readRequestedRoute(intent: Intent?) {
        intent?.getStringExtra(EXTRA_DESTINATION)?.let { requestedRoute.value = it }
    }

    companion object {
        /** Маршрут [com.valerochka1337.valerochkagym.ui.navigation.GymRoutes], который надо открыть. */
        const val EXTRA_DESTINATION = "com.valerochka1337.valerochkagym.extra.DESTINATION"
    }
}
