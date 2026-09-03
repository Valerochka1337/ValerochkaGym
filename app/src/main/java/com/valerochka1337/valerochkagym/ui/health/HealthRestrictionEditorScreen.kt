package com.valerochka1337.valerochkagym.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.domain.health.HealthRestrictionState

@Composable
fun HealthRestrictionEditorScreen(
    onBack: () -> Unit,
    viewModel: HealthRestrictionEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = gymHaptics()
    LaunchedEffect(Unit) { viewModel.finished.collect { haptics.success(); onBack() } }
    state.disclosure?.let { disclosure -> AlertDialog(
        onDismissRequest = { haptics.reject(); viewModel.cancelDisclosure() },
        title = { Text(if (disclosure.loopbackWarning) "Подтвердите локальный HTTP" else "Отправить исходную формулировку?") },
        text = { Text("Получатель: ${disclosure.host}\nМодель: ${disclosure.model}\nБудет передана текущая исходная формулировка и структурированный запрос.") },
        confirmButton = { TextButton(onClick = { haptics.confirm(); viewModel.confirmDisclosure() }) { Text(if (disclosure.loopbackWarning) "Подтвердить локальную отправку" else "Отправить") } },
        dismissButton = { TextButton(onClick = { haptics.reject(); viewModel.cancelDisclosure() }) { Text("Отмена") } },
    ) }
    GlowBackground {
        Column(Modifier.fillMaxSize()) {
            HealthScreenHeader("Ограничения", onBack)
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GymCard(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = state.originalText,
                        onValueChange = viewModel::original,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Исходная формулировка") },
                        minLines = 3,
                    )
                    OutlinedButton(
                        onClick = { haptics.tap(); viewModel.requestInterpretation() },
                        enabled = !state.reading,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(if (state.reading) "Читаем…" else "Предложить структуру") }
                    Text("Автоматическое толкование — только черновик. Подтвердите каждое ограничение вручную.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.proposals.forEachIndexed { index, proposal ->
                    GymCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = proposal.included,
                                onCheckedChange = { checked -> haptics.toggle(checked); viewModel.update(index, proposal.copy(included = checked)) },
                            )
                            Text("Подтвердить ограничение", style = MaterialTheme.typography.titleMedium)
                        }
                        OutlinedTextField(
                            value = proposal.text,
                            onValueChange = { viewModel.update(index, proposal.copy(text = it)) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Что ограничить") },
                        )
                        Text("Статус: ${proposal.state.label()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { haptics.tap(); viewModel.add() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Добавить вручную") }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                PillButton(
                    text = "Подтвердить ограничения",
                    onClick = { haptics.confirm(); viewModel.save() },
                    enabled = !state.reading,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun HealthRestrictionState.label(): String = when (this) {
    HealthRestrictionState.ACTIVE -> "активно"
    HealthRestrictionState.TEMPORARY -> "временно"
    HealthRestrictionState.LIFTED -> "снято"
}
