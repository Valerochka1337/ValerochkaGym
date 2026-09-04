package com.valerochka1337.valerochkagym.ui.analysis.body

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.domain.displayName
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics

internal object MuscleSelectorState {
    const val VIRTUAL_ITEMS = 10_000
    private const val RECENTER_BASE = VIRTUAL_ITEMS / 2
    data class VisibleItem(val index: Int, val offset: Int, val size: Int)
    fun wrapped(index: Int): Muscle = Muscle.entries[Math.floorMod(index, Muscle.entries.size)]
    fun next(current: Muscle, delta: Int): Muscle = wrapped(Muscle.entries.indexOf(current) + delta)
    fun centeredIndex(items: List<VisibleItem>, viewportStart: Int, viewportEnd: Int): Int? {
        val center = (viewportStart + viewportEnd) / 2
        return items.minByOrNull { kotlin.math.abs((it.offset + it.size / 2) - center) }?.index
    }
    fun centerFor(muscle: Muscle): Int = RECENTER_BASE + Muscle.entries.indexOf(muscle)
    fun shouldRecenter(index: Int): Boolean = index < Muscle.entries.size * 2 || index > VIRTUAL_ITEMS - Muscle.entries.size * 2
}

/** Finite accessible projection of the cyclic muscle order; no gesture is required. */
@Composable
fun MuscleSelector(
    selected: Muscle?,
    roleText: (Muscle) -> String,
    onSelected: (Muscle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = gymHaptics()
    var listOpen by remember { mutableStateOf(false) }
    val current = selected ?: Muscle.entries.first()
    val index = Muscle.entries.indexOf(current)
    val carousel = rememberLazyListState(initialFirstVisibleItemIndex = MuscleSelectorState.centerFor(current))
    val snap = rememberSnapFlingBehavior(lazyListState = carousel)
    var lastSettled by remember { mutableStateOf(current) }
    fun selectChanged(muscle: Muscle) {
        if (muscle != lastSettled) {
            lastSettled = muscle
            haptics.tap()
            onSelected(muscle)
        }
    }
    LaunchedEffect(current) {
        if (!carousel.isScrollInProgress) carousel.scrollToItem(MuscleSelectorState.centerFor(current))
        lastSettled = current
    }
    LaunchedEffect(carousel) {
        snapshotFlow { carousel.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                val info = carousel.layoutInfo
                val centered = MuscleSelectorState.centeredIndex(
                    info.visibleItemsInfo.map { MuscleSelectorState.VisibleItem(it.index, it.offset, it.size) },
                    info.viewportStartOffset,
                    info.viewportEndOffset,
                ) ?: return@collect
                val muscle = MuscleSelectorState.wrapped(centered)
                selectChanged(muscle)
                if (MuscleSelectorState.shouldRecenter(centered)) carousel.scrollToItem(MuscleSelectorState.centerFor(muscle))
            }
        }
    }
    fun move(delta: Int) {
        val next = Muscle.entries[(index + delta + Muscle.entries.size) % Muscle.entries.size]
        selectChanged(next)
    }
    Column(
        modifier = modifier.semantics {
            stateDescription = "${current.displayName()}: ${roleText(current)}"
            customActions = listOf(
                CustomAccessibilityAction("Предыдущая мышца") { move(-1); true },
                CustomAccessibilityAction("Следующая мышца") { move(1); true },
                CustomAccessibilityAction("Открыть весь список") { listOpen = true; true },
            )
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { move(-1) }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Предыдущая мышца")
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(current.displayName(), style = MaterialTheme.typography.titleMedium)
                Text(roleText(current), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { move(1) }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Следующая мышца")
            }
        }
        LazyRow(state = carousel, flingBehavior = snap, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(count = MuscleSelectorState.VIRTUAL_ITEMS) { virtual ->
                val muscle = MuscleSelectorState.wrapped(virtual)
                FilterChip(selected = muscle == current, onClick = { selectChanged(muscle) }, label = { Text(muscle.displayName()) })
            }
        }
        TextButton(onClick = { listOpen = !listOpen }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (listOpen) "Скрыть список" else "Открыть весь список")
        }
        if (listOpen) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(Muscle.entries.toList(), key = Muscle::name) { muscle ->
                    FilterChip(
                        selected = muscle == current,
                        onClick = { selectChanged(muscle) },
                        label = { Text(muscle.displayName()) },
                    )
                }
            }
        }
    }
}
