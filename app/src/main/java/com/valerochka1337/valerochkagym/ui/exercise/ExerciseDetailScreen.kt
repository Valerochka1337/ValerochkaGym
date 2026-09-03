package com.valerochka1337.valerochkagym.ui.exercise

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseVariantEntity
import com.valerochka1337.valerochkagym.domain.ExerciseStatistics
import com.valerochka1337.valerochkagym.domain.displayName
import com.valerochka1337.valerochkagym.ui.analysis.formatDate
import com.valerochka1337.valerochkagym.ui.analysis.formatDateWithYear
import com.valerochka1337.valerochkagym.ui.analysis.charts.LinePoint
import com.valerochka1337.valerochkagym.ui.analysis.charts.TrendLineChart
import com.valerochka1337.valerochkagym.ui.analysis.body.BodyMapFlip
import com.valerochka1337.valerochkagym.ui.components.CircleIconButton
import com.valerochka1337.valerochkagym.ui.components.ExerciseAvatar
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.library.ExerciseEditorSheet
import com.valerochka1337.valerochkagym.ui.theme.ChartPalette
import java.math.BigDecimal
import java.time.ZoneId

@Composable
fun ExerciseDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExerciseDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val haptics = gymHaptics()
    var variantsOpen by rememberSaveable { mutableStateOf(false) }

    GlowBackground(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            ExerciseHeader(
                exercise = state.exercise,
                onBack = onBack,
                onEdit = {
                    haptics.tap()
                    viewModel.openEditor()
                },
            )
            when {
                state.loading -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
                state.exercise == null -> MissingExercise()
                else -> ExerciseDetailContent(
                    exercise = state.exercise!!,
                    loads = state.loads,
                    statistics = state.statistics,
                    variants = state.variants,
                    onManageVariants = { variantsOpen = true },
                )
            }
        }
    }

    editor?.let { initial ->
        ExerciseEditorSheet(
            initial = initial,
            onDismiss = viewModel::closeEditor,
            onSave = viewModel::saveEditor,
        )
    }
    val selectedExercise = state.exercise
    if (variantsOpen && selectedExercise != null) {
        ExerciseVariantEditorSheet(
            exerciseName = selectedExercise.name,
            variants = state.variants,
            error = state.variantError,
            onDismiss = { variantsOpen = false },
            onSave = viewModel::saveVariant,
            onArchive = viewModel::archiveVariant,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ExerciseHeader(
    exercise: ExerciseEntity?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Назад",
                )
            }
        },
        title = {
            Column {
                Text(
                    text = exercise?.name ?: "Упражнение",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                )
                exercise?.let {
                    Text(
                        text = "${it.muscleGroup.displayName()} · ${it.type.displayName()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        },
        actions = {
            if (exercise != null) {
            CircleIconButton(
                icon = Icons.Rounded.Edit,
                contentDescription = "Редактировать упражнение",
                onClick = onEdit,
            )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

@Composable
internal fun ExerciseDetailContent(
    exercise: ExerciseEntity,
    loads: List<MuscleLoad>,
    statistics: ExerciseStatistics?,
    variants: List<ExerciseVariantEntity> = emptyList(),
    onManageVariants: () -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { MusclesCard(loads) }
        item { VariantsCard(variants, onManageVariants) }
        if (statistics != null) {
            if (statistics.hasData) {
                item { LastTimeCard(statistics) }
                if (statistics.points.isNotEmpty()) item { ProgressCard(statistics) }
                if (statistics.records.isNotEmpty()) item { RecordsCard(statistics) }
            } else {
                item { EmptyStatisticsCard() }
            }
        }
    }
}

@Composable
private fun VariantsCard(variants: List<ExerciseVariantEntity>, onManage: () -> Unit) {
    GymCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Варианты выполнения",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        TextButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) { Text("Управлять вариантами") }
        Spacer(Modifier.height(6.dp))
        if (variants.isEmpty()) {
            Text(
                text = "Создайте вариант, чтобы вести отдельную историю для другого способа выполнения.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            variants.forEach { variant ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (variant.isArchived) "${variant.name} · в архиве" else variant.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(exercise: ExerciseEntity) {
    GymCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExerciseAvatar(exercise = exercise)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = exercise.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${exercise.muscleGroup.displayName()} · ${exercise.type.displayName()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MusclesCard(loads: List<MuscleLoad>) {
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val accent = MaterialTheme.colorScheme.primary
    GymCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Вовлечение мышц",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (loads.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Карта мышц пока не заполнена",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@GymCard
        }
        Spacer(Modifier.height(8.dp))
        val byMuscle = loads.associate { it.muscle to it.contribution }
        BodyMapFlip(
            fillFor = { muscle ->
                val load = byMuscle[muscle] ?: return@BodyMapFlip ChartPalette.Empty
                lerp(base, accent, load / 100f)
            },
        )
        Spacer(Modifier.height(8.dp))
        loads.forEach { load ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = load.muscle.displayName(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${load.contribution}%",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { load.contribution / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun LastTimeCard(statistics: ExerciseStatistics) {
    val date = statistics.lastPerformedAt ?: return
    StatisticCardHeader(Icons.Rounded.History, "Прошлый раз") {
        Text(
            text = formatDateWithYear(date, ZoneId.systemDefault()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = statistics.lastSummary.ifBlank { "Выполненные подходы без числовых значений" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ProgressCard(statistics: ExerciseStatistics) {
    var selectedIndex by rememberSaveable(statistics.points) { mutableIntStateOf(statistics.points.lastIndex) }
    val selected = statistics.points[selectedIndex.coerceIn(statistics.points.indices)]
    StatisticCardHeader(Icons.AutoMirrored.Rounded.ShowChart, statistics.chartTitle) {
        Text(
            text = statistics.chartSubtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        TrendLineChart(
            points = statistics.points.map { point ->
                LinePoint(
                    xMillis = point.dateMillis,
                    y = point.value.toFloat(),
                    xLabel = formatDate(point.dateMillis, ZoneId.systemDefault()),
                )
            },
            selectedIndex = selectedIndex,
            onSelect = { index -> if (index != null) selectedIndex = index },
            valueFormatter = { value -> "${number(value.toDouble())} ${statistics.chartUnit}" },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${formatDateWithYear(selected.dateMillis, ZoneId.systemDefault())} · ${selected.summary}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun RecordsCard(statistics: ExerciseStatistics) {
    StatisticCardHeader(Icons.Rounded.EmojiEvents, "Рекорды") {
        statistics.records.forEachIndexed { index, record ->
            if (index > 0) Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    record.details?.let { details ->
                        Text(
                            text = details,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = record.value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun EmptyStatisticsCard() {
    GymCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Статистика",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Завершите тренировку с этим упражнением — здесь появятся прошлый результат, график и рекорды.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatisticCardHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable () -> Unit,
) {
    GymCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun MissingExercise() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Упражнение не найдено",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun number(value: Double): String =
    BigDecimal.valueOf(value).setScale(1, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString()
