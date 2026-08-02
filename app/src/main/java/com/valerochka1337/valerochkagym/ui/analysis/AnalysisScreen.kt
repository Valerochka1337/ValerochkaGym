package com.valerochka1337.valerochkagym.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.R
import com.valerochka1337.valerochkagym.domain.analysis.AnalysisPeriod
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymTopBar

/**
 * Вкладка «Анализы»: тепловая карта нагрузки по мышцам, недельный объём, прогресс силы по
 * упражнениям и производные показатели (скачок нагрузки, баланс, частота, рекорды).
 *
 * Порядок карточек — от «что делать сейчас» к «как идут дела»: сводка, карта тела и объём по
 * мышцам отвечают на вопрос «чего не хватает», прогресс и рекорды — «работает ли план».
 *
 * Переключатель периода стоит **один раз над всем** содержимым: у каждой карточки свой фильтр
 * означал бы, что графики на экране показывают разные срезы и их нельзя сравнивать.
 */
@Composable
fun AnalysisScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AnalysisViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    GlowBackground(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            GymTopBar(title = "Анализы", onOpenSettings = onOpenSettings)

            if (!state.report.hasData) {
                if (!state.loading) EmptyState(modifier = Modifier.weight(1f))
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    ChipRow(
                        options = AnalysisPeriod.entries,
                        selected = state.period,
                        label = { it.displayName() },
                        onSelect = viewModel::onPeriodSelected,
                    )
                }
                item { SummaryCard(state) }
                item { MuscleHeatmapCard(state = state, onMuscleClicked = viewModel::onMuscleClicked) }
                item { MuscleVolumeCard(state = state, onMuscleClicked = viewModel::onMuscleClicked) }
                item { MuscleFrequencyCard(state) }
                item {
                    WeeklyVolumeCard(
                        state = state,
                        onMetricSelected = viewModel::onWeeklyMetricSelected,
                        onWeekSelected = viewModel::onWeekSelected,
                    )
                }
                item { WorkloadCard(state.report.workload) }
                item { BalanceCard(state.report.balances) }
                item {
                    ExerciseProgressCard(
                        state = state,
                        onExerciseSelected = viewModel::onExerciseSelected,
                        onSessionSelected = viewModel::onSessionSelected,
                    )
                }
                item { RecordsCard(state) }
            }
        }
    }
}

/** Пустая история: объясняем, что появится, вместо графиков из нулей. */
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_tab_analysis),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Пока нечего анализировать",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Завершите первую тренировку — появятся карта нагрузки по мышцам, недельный объём " +
                "и графики прогресса по каждому упражнению.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
