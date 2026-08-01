package com.valerochka1337.valerochkagym.ui.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.domain.PrResult
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.components.PillButton
import java.math.BigDecimal

/**
 * Итоги завершённой тренировки: длительность, объём, новые рекорды и сводка упражнений.
 * Если факт разошёлся с программой — один раз предлагает обновить программу. [onDone]
 * возвращает на главную.
 */
@Composable
fun WorkoutSummaryScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorkoutSummaryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    GlowBackground(modifier = modifier) {
        if (state.loading) return@GlowBackground

        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "Тренировка завершена",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
            )
            Text(
                text = state.workoutName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    StatsCard(
                        durationSeconds = state.durationSeconds,
                        volumeKg = state.volumeKg,
                    )
                }

                if (state.prs.isNotEmpty()) {
                    item {
                        Text(
                            text = "Новые рекорды",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    items(state.prs) { pr ->
                        PrCard(pr)
                    }
                }

                if (state.exercises.isNotEmpty()) {
                    item {
                        Text(
                            text = "Упражнения",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    items(state.exercises) { exercise ->
                        GymCard(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                        ) {
                            Text(
                                text = exercise.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (exercise.setsSummary.isNotEmpty()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = exercise.setsSummary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            PillButton(
                text = "Готово",
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
            )
        }
    }

    if (state.showUpdateRoutineDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRoutineUpdate,
            title = { Text("Обновить программу?") },
            text = {
                Text("Фактически выполненные упражнения и подходы отличаются от программы. Перезаписать программу по факту тренировки?")
            },
            confirmButton = {
                TextButton(onClick = viewModel::applyRoutineUpdate) {
                    Text("Обновить")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRoutineUpdate) {
                    Text("Не сейчас")
                }
            },
        )
    }
}

@Composable
private fun StatsCard(
    durationSeconds: Long,
    volumeKg: Double,
) {
    GymCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(20.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            StatColumn(
                label = "Длительность",
                value = formatDuration(durationSeconds),
                accent = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            StatColumn(
                label = "Объём",
                value = "${formatNumber(volumeKg)} кг",
                accent = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatColumn(
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
    }
}

@Composable
private fun PrCard(pr: PrResult) {
    GymCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = pr.exerciseName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${formatNumber(pr.weightKg)} кг",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) "$hours ч $minutes мин" else "$minutes мин"
}

/** Число без хвостовых нулей, локаль-независимо. */
private fun formatNumber(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
