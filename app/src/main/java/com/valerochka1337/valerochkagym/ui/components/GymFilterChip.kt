package com.valerochka1337.valerochkagym.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.valerochka1337.valerochkagym.ui.theme.GymMotion

/**
 * FilterChip с анимированным выделением: фон и текст переезжают в выбранное состояние пружиной
 * эффектов, а не скачком. M3-чип сам цвет не анимирует — он мгновенно меняет слот selected,
 * поэтому оба слота получают одно и то же анимированное значение.
 */
@Composable
fun GymFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = GymMotion.effectsFast(),
        label = "chip-container",
    )
    val content by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = GymMotion.effectsFast(),
        label = "chip-content",
    )
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = container,
            labelColor = content,
            selectedContainerColor = container,
            selectedLabelColor = content,
        ),
    )
}
