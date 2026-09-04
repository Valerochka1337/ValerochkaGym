package com.valerochka1337.valerochkagym.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Shared, stateless dialog for creating a program snapshot from a completed workout. */
@Composable
fun SaveWorkoutAsProgramDialog(
    name: String,
    isSaving: Boolean,
    error: String?,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Сохранить как программу") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Название программы" },
                enabled = !isSaving,
                singleLine = true,
                label = { Text("Название программы") },
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = name.isNotBlank() && !isSaving,
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "Сохранить" },
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics { contentDescription = "Сохраняем программу" },
                    )
                } else {
                    Text("Сохранить")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Text("Отмена")
            }
        },
    )
}
