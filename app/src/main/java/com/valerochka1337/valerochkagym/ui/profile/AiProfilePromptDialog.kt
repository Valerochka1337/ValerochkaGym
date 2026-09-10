package com.valerochka1337.valerochkagym.ui.profile

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.valerochka1337.valerochkagym.data.profile.AiProfilePromptKind

data class AiProfilePromptUi(val token: String, val kind: AiProfilePromptKind)

/** UI only: callers retain their live action and consume the gate token before continuing it. */
@Composable
fun AiProfilePromptDialog(
    token: String,
    onVisible: (String) -> Unit,
    onFillProfile: (String) -> Unit,
    onContinue: (String) -> Unit,
    onDisable: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
  LaunchedEffect(token) { onVisible(token) }
  AlertDialog(
      onDismissRequest = { onDismiss(token) },
      title = { Text("Сделать подсказку точнее?") },
      text = {
        Text("Цель и опыт помогут точнее подготовить подсказку. Можно продолжить без профиля.")
      },
      confirmButton = {
        TextButton(
            onClick = { onFillProfile(token) },
            modifier =
                androidx.compose.ui.Modifier.semantics { contentDescription = "Заполнить профиль" },
        ) {
          Text("Заполнить")
        }
      },
      dismissButton = {
        TextButton(
            onClick = { onContinue(token) },
            modifier =
                androidx.compose.ui.Modifier.semantics {
                  contentDescription = "Продолжить без заполнения"
                },
        ) {
          Text("Продолжить")
        }
        TextButton(
            onClick = { onDisable(token) },
            modifier =
                androidx.compose.ui.Modifier.semantics {
                  contentDescription = "Не предлагать профиль"
                },
        ) {
          Text("Не предлагать")
        }
      },
  )
}
