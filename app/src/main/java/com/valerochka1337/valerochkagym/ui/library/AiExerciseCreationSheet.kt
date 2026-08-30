package com.valerochka1337.valerochkagym.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.ui.components.FadeInContent
import com.valerochka1337.valerochkagym.ui.components.PillButton

/**
 * Первая шторка по кнопке «+»: ИИ получает описание и каталог, но сохранение остаётся за
 * пользователем в обычном [ExerciseEditorSheet].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiExerciseCreationSheet(
    state: ExerciseAiCreationState,
    onDescriptionChange: (String) -> Unit,
    onGenerate: () -> Unit,
    onCreateManually: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Новое упражнение",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = state.description,
                onValueChange = onDescriptionChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isGenerating,
                minLines = 4,
                maxLines = 7,
                label = { Text("Описание упражнения") },
                placeholder = { Text("Например: тяга гантели к поясу одной рукой в наклоне") },
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onGenerate() }),
            )

            if (state.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (state.modelUnavailable) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenSettings, enabled = !state.isGenerating) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Выбрать другую модель")
                    }
                }
            }

            if (!state.aiConfigured) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Настройте нейросеть в настройках.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenSettings, enabled = !state.isGenerating) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Открыть настройки")
                }
            }

            if (state.isGenerating) {
                Spacer(Modifier.height(16.dp))
                FadeInContent {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularWavyProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            waveSpeed = WavyProgressIndicatorDefaults.CircularWavelength * 1.5f,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Создаю упражнение…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            PillButton(
                text = if (state.isGenerating) "Создаю…" else "Создать с ИИ",
                onClick = onGenerate,
                enabled = !state.isGenerating &&
                    state.aiConfigured &&
                    state.description.trim().isNotEmpty(),
                leadingIcon = Icons.Rounded.AutoAwesome,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = onCreateManually, enabled = !state.isGenerating) {
                    Text("Создать вручную")
                }
                TextButton(onClick = onDismiss) {
                    Text("Отмена")
                }
            }
        }
    }
}
