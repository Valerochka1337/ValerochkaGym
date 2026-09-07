package com.valerochka1337.valerochkagym.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.ui.account.AccountForm
import com.valerochka1337.valerochkagym.ui.account.AccountViewModel
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.robolectric.annotation.Config

@Config(qualifiers = "w360dp-h640dp-xhdpi")
class AccountFormComposeTest : RoomDaoTest() {
  @get:Rule val compose = createComposeRule()
  private val calls = mutableListOf<String>()
  private var googleClicks = 0

  private fun render() {
    val store =
        object : BackendSessionStore {
          override val session = MutableStateFlow<BackendTokens?>(null)

          override fun save(tokens: BackendTokens?) {
            session.value = tokens
          }
        }
    val api =
        object : BackendTransport {
          override val json = Json

          override suspend fun public(
              method: String,
              path: String,
              body: JsonElement?,
          ): JsonElement {
            calls += path
            return buildJsonObject {}
          }

          override suspend fun authorized(
              method: String,
              path: String,
              body: JsonElement?,
          ): JsonElement = error("Unexpected")
        }
    val vm =
        AccountViewModel(
            api,
            store,
            BackendSync(db, api, store),
            BackendSyncScheduler(ApplicationProvider.getApplicationContext<Context>(), db),
        )
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        GymTheme {
          Column(Modifier.verticalScroll(rememberScrollState())) {
            AccountForm(vm, onGoogleSignIn = { googleClicks++ })
          }
        }
      }
    }
  }

  @Test
  fun `login validates fields without a migration checkbox at large font scale`() {
    render()
    compose.onNodeWithText("Войти").performScrollTo().assertIsEnabled().performClick()
    compose
        .onNodeWithText("Введите email, например name@mail.ru")
        .performScrollTo()
        .assertIsDisplayed()
    compose.onNodeWithText("Введите пароль").performScrollTo().assertIsDisplayed()
    assertTrue(calls.isEmpty())
    compose.onNodeWithText("Экспортировать локальную базу").assertDoesNotExist()
    compose.onNodeWithContentDescription("Показать пароль").performScrollTo().performClick()
    compose.onNodeWithContentDescription("Скрыть пароль").assertIsDisplayed()
  }

  @Test
  fun `registration and recovery remain reachable at large font scale`() {
    render()
    compose.onNodeWithText("Создать аккаунт").performScrollTo().performClick()
    compose.onNodeWithText("От 12 до 128 символов").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("Вернуться ко входу").performScrollTo().performClick()
    compose.onNodeWithText("Забыли пароль?").performScrollTo().performClick()
    compose.onNodeWithText("Получить код").performScrollTo().assertIsEnabled()
    compose.onNodeWithText("Пароль").assertDoesNotExist()
  }

  @Test
  fun `Google sign in works without email fields on both login and registration`() {
    render()
    compose.onNodeWithText("Продолжить с Google").performScrollTo().assertIsEnabled().performClick()
    assertEquals(1, googleClicks)
    assertTrue(calls.isEmpty())
    compose.onNodeWithText("Создать аккаунт").performScrollTo().performClick()
    compose.onNodeWithText("Продолжить с Google").performScrollTo().assertIsEnabled().performClick()
    assertEquals(2, googleClicks)
    compose.onNodeWithText("Вернуться ко входу").performScrollTo().performClick()
    compose.onNodeWithText("Забыли пароль?").performScrollTo().performClick()
    compose.onNodeWithText("Продолжить с Google").assertDoesNotExist()
  }
}
