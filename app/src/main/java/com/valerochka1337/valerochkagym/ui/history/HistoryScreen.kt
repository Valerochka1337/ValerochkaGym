package com.valerochka1337.valerochkagym.ui.history

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.components.GymCard

/**
 * Вкладка «История»: список завершённых тренировок. Тап по карточке открывает детальный экран
 * через [onWorkoutClick]. Пока список не загружен — ничего не показываем (Room отдаёт быстро),
 * пустой список показывает пустое состояние.
 */
@Composable
fun HistoryScreen(
    onWorkoutClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    GlowBackground(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "История",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 12.dp),
            )

            val workouts = state.workouts
            when {
                // Ещё не загружено: ничего не показываем.
                workouts == null -> Unit
                workouts.isEmpty() -> EmptyState(modifier = Modifier.weight(1f))
                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(workouts, key = { it.id }) { workout ->
                        HistoryCard(item = workout, onClick = { onWorkoutClick(workout.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(
    item: HistoryItemUi,
    onClick: () -> Unit,
) {
    GymCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle(item),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            UploadStatusBadge(status = item.uploadStatus)
        }
    }
}

/** «31 июля · 45 мин» плюс объём, если он есть. */
private fun subtitle(item: HistoryItemUi): String = buildString {
    append(item.date)
    append(" · ")
    append(item.duration)
    item.volume?.let {
        append(" · ")
        append(it)
    }
}

/** Бейдж статуса выгрузки: нейтральный «Ожидает», teal «Выгружено», error-тон «Ошибка». */
@Composable
fun UploadStatusBadge(status: UploadStatus) {
    val (label, color) = when (status) {
        UploadStatus.PENDING -> "Ожидает" to MaterialTheme.colorScheme.onSurfaceVariant
        UploadStatus.UPLOADED -> "Выгружено" to MaterialTheme.colorScheme.primary
        UploadStatus.FAILED -> "Ошибка" to MaterialTheme.colorScheme.error
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Пока нет завершённых тренировок",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Завершённые тренировки будут появляться здесь.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
