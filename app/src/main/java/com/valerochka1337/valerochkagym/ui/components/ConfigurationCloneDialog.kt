package com.valerochka1337.valerochkagym.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun ConfigurationCloneDialog(
    state: ConfigurationCloneUiState,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
  val target = state.target ?: return
  val type = if (target is ConfigurationCloneTarget.Routine) "программу" else "зал"
  AlertDialog(
      onDismissRequest = { if (!state.isSaving) onDismiss() },
      title = { Text("Клонировать $type") },
      text = {
        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Название копии" },
            enabled = !state.isSaving,
            singleLine = true,
            label = { Text("Название") },
            isError = state.error != null,
            supportingText =
                state.error?.let { error ->
                  { Text(error, color = MaterialTheme.colorScheme.error) }
                },
        )
      },
      confirmButton = {
        TextButton(
            onClick = onSave,
            enabled = state.name.isNotBlank() && !state.isSaving,
            modifier =
                Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                  contentDescription = "Сохранить"
                },
        ) {
          if (state.isSaving) {
            CircularProgressIndicator(
                modifier = Modifier.semantics { contentDescription = "Сохраняем копию" },
            )
          } else {
            Text("Сохранить")
          }
        }
      },
      dismissButton = {
        TextButton(
            onClick = onDismiss,
            enabled = !state.isSaving,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) {
          Text("Отмена")
        }
      },
  )
}
