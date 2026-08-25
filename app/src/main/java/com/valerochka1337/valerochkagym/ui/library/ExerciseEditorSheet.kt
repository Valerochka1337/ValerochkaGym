package com.valerochka1337.valerochkagym.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad
import com.valerochka1337.valerochkagym.data.db.entity.group
import com.valerochka1337.valerochkagym.domain.displayName
import com.valerochka1337.valerochkagym.ui.analysis.body.BodyMapFlip
import com.valerochka1337.valerochkagym.ui.analysis.body.offFigureMuscles
import com.valerochka1337.valerochkagym.ui.components.PillButton
import com.valerochka1337.valerochkagym.ui.theme.ChartPalette

/** Шаг ползунка вовлечения: 5% — предел осмысленной точности для такой оценки. */
private const val LOAD_STEP = 5

/**
 * Редактор упражнения: название, тип и разметка мышц на интерактивной модели тела.
 *
 * Разметка задаётся не списком галочек, а по фигуре: тап по мышце добавляет её, ползунок
 * ставит долю вовлечения 0–100%. Именно эти доли потом определяют, сколько «эффективных
 * подходов» получает каждая мышца в аналитике, поэтому шкала подписана прямо в шторке.
 *
 * Заливка мышцы — одна зелёная шкала от тона поверхности к акценту: здесь цвет кодирует
 * величину одной переменной, поэтому многоцветная шкала была бы ошибкой (в отличие от карты
 * нагрузки, где ступени означают качественно разные зоны).
 *
 * Рабочая копия живёт в [remember], а не в `rememberSaveable`: у Muscle-ключей нет
 * Parcelable-представления, а незакрытая шторка при смене конфигурации и так закрывается.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExerciseEditorSheet(
    initial: ExerciseEditorState,
    onDismiss: () -> Unit,
    onSave: (name: String, type: ExerciseType, loads: List<MuscleLoad>) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var typeName by remember(initial) { mutableStateOf(initial.type.name) }
    var active by remember(initial) { mutableStateOf<Muscle?>(null) }
    val loads = remember(initial) { mutableStateMapOf<Muscle, Int>().apply { putAll(initial.loads) } }
    val type = ExerciseType.valueOf(typeName)

    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val accent = MaterialTheme.colorScheme.primary
    val canSave = name.trim().isNotEmpty() && loads.isNotEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = if (initial.exerciseId == null) "Своё упражнение" else initial.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (initial.wasFoundByAi) {
                Spacer(Modifier.height(12.dp))
                FoundExistingExerciseNotice()
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = initial.editableName,
                label = { Text("Название") },
                shape = RoundedCornerShape(16.dp),
            )

            Spacer(Modifier.height(16.dp))
            SheetLabel("Тип")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExerciseType.entries.forEach { entry ->
                    FilterChip(
                        selected = entry == type,
                        onClick = { typeName = entry.name },
                        label = { Text(entry.displayName()) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            SheetLabel("Какие мышцы работают")
            Text(
                text = "Общая шкала для всех упражнений: 100% — прямая нагрузка тяжёлого силового подхода, " +
                    "60–85% — сильная, 25–55% — умеренная, меньше — стабилизация или выносливость. " +
                    "Максимум не обязан быть 100%.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            BodyMapFlip(
                fillFor = { muscle ->
                    val load = loads[muscle]
                    if (load == null || load <= 0) ChartPalette.Empty else lerp(base, accent, load / 100f)
                },
                selectedMuscle = active,
                onMuscleClick = { muscle ->
                    if (muscle == null) {
                        active = null
                    } else {
                        active = muscle
                        // Первый тап сразу даёт мышце вес: иначе нажатие выглядит «ничего не сделал».
                        if (loads[muscle] == null) loads[muscle] = DEFAULT_LOAD
                    }
                },
            )

            // Мышцы без своей области на фигуре не выбрать тапом — добавляем их кнопками.
            val addable = offFigureMuscles.filter { it !in loads.keys }
            if (addable.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    addable.forEach { muscle ->
                        AssistChip(
                            onClick = {
                                active = muscle
                                if (loads[muscle] == null) loads[muscle] = DEFAULT_LOAD
                            },
                            label = { Text(muscle.displayName()) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }

            val activeMuscle = active
            if (activeMuscle != null) {
                Spacer(Modifier.height(12.dp))
                ActiveMuscleEditor(
                    muscle = activeMuscle,
                    value = loads[activeMuscle] ?: DEFAULT_LOAD,
                    onValueChange = { loads[activeMuscle] = it },
                    onRemove = {
                        loads.remove(activeMuscle)
                        active = null
                    },
                )
            }

            if (loads.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                SheetLabel("Выбрано")
                SelectedMuscles(
                    loads = loads,
                    active = active,
                    onSelect = { active = it },
                )
                if (initial.exerciseId == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Группа в библиотеке: ${primaryGroupLabel(loads)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton(
                    text = when {
                        initial.exerciseId == null -> "Создать"
                        initial.wasFoundByAi -> "Изменить найденное"
                        else -> "Сохранить"
                    },
                    onClick = {
                        onSave(
                            name,
                            type,
                            loads.entries.map { MuscleLoad(it.key, it.value) }.sortedByDescending { it.contribution },
                        )
                    },
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        }
    }
}

/** Поясняет, что ИИ открыл существующую запись, а не подготовил новую. */
@Composable
private fun FoundExistingExerciseNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Упражнение найдено в библиотеке",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "ИИ не создал новую запись. После сохранения изменится существующее упражнение.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ActiveMuscleEditor(
    muscle: Muscle,
    value: Int,
    onValueChange: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = muscle.displayName(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$value%",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Убрать мышцу",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange((it / LOAD_STEP).toInt() * LOAD_STEP) },
            valueRange = LOAD_STEP.toFloat()..100f,
            steps = 100 / LOAD_STEP - 2,
        )
    }
}

@Composable
private fun SelectedMuscles(
    loads: Map<Muscle, Int>,
    active: Muscle?,
    onSelect: (Muscle) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        loads.entries.sortedByDescending { it.value }.forEach { (muscle, load) ->
            FilterChip(
                selected = muscle == active,
                onClick = { onSelect(muscle) },
                label = { Text("${muscle.displayName()} $load%") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
}

/** Крупная группа упражнения выводится из самой вовлечённой мышцы — вручную её выбирать не нужно. */
private fun primaryGroupLabel(loads: Map<Muscle, Int>): String =
    loads.maxByOrNull { it.value }?.key?.group()?.displayName() ?: "—"

private const val DEFAULT_LOAD = 50
