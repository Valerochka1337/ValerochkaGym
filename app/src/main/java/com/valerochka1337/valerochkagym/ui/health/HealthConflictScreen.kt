package com.valerochka1337.valerochkagym.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.health.ConflictChoice
import com.valerochka1337.valerochkagym.ui.analysis.conflictCategoryName
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics

@Composable
fun HealthConflictScreen(
    onBack: () -> Unit,
    viewModel: HealthConflictViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = gymHaptics()
    LaunchedEffect(Unit) { viewModel.events.collect { haptics.success(); onBack() } }
    GlowBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            HealthScreenHeader("Разрешение конфликта", onBack)
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GymCard(Modifier.fillMaxWidth()) {
                    state.conflict?.let { conflict ->
                        Text("${conflictCategoryName(conflict.category)} · версия ${conflict.version}", style = MaterialTheme.typography.titleMedium)
                        Text("Выбор создаст новую локальную версию. Исходные версии останутся в истории.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } ?: Text("Конфликт не найден. Он мог быть разрешён в другом действии.")
                }
                state.local?.let { ConflictVersionCard("Локальная", it) }
                state.remote?.let { ConflictVersionCard("Из Google Sheets", it) }
                GymCard(Modifier.fillMaxWidth()) {
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(
                        onClick = { haptics.confirm(); viewModel.choose(ConflictChoice.LOCAL) },
                        enabled = state.canResolve,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(if (state.resolving) "Сохраняем…" else "Сохранить локальную как новую версию") }
                    TextButton(
                        onClick = { haptics.confirm(); viewModel.choose(ConflictChoice.REMOTE) },
                        enabled = state.canResolve,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Принять версию из Google Sheets") }
                }
            }
        }
    }
}

@Composable
private fun ConflictVersionCard(label: String, payload: HealthConflictPayloadView) {
    GymCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(payload.title, style = MaterialTheme.typography.titleSmall)
            payload.rows.forEach { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
