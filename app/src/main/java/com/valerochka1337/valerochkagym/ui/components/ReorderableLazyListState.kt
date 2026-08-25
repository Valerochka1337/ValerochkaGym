package com.valerochka1337.valerochkagym.ui.components

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Единая зона у верхнего и нижнего края списка, в которой перенос прокручивает его дальше. */
private val ReorderAutoScrollZone = 96.dp

/**
 * Состояние перестановки вертикального списка с одинаковой для приложения зоной автопрокрутки.
 *
 * Общий helper не даёт экранам случайно разойтись в поведении длинных списков при drag-and-drop.
 */
@Composable
internal fun rememberGymReorderableLazyListState(
    lazyListState: LazyListState,
    onMove: suspend CoroutineScope.(from: LazyListItemInfo, to: LazyListItemInfo) -> Unit,
): ReorderableLazyListState = rememberReorderableLazyListState(
    lazyListState = lazyListState,
    scrollThreshold = ReorderAutoScrollZone,
    onMove = onMove,
)
