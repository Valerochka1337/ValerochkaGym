package com.valerochka1337.valerochkagym.ui.analysis.body

import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.domain.displayName
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Pure virtual-wheel math. A virtual index is intentionally never part of the UI contract. */
internal object MuscleSelectorState {
    private const val VIRTUAL_ITEM_COUNT = 1_000_001
    private const val MIN_FOCUS_SCALE = 0.96f
    private const val MIN_FOCUS_ALPHA = 0.72f

    /** A middle index which resolves to the first logical muscle. */
    val middleAnchor: Int = VIRTUAL_ITEM_COUNT / 2 - Math.floorMod(VIRTUAL_ITEM_COUNT / 2, Muscle.entries.size)

    fun next(current: Muscle, delta: Int): Muscle = muscleAt(Muscle.entries.indexOf(current) + delta)

    fun muscleAt(virtualIndex: Int): Muscle =
        Muscle.entries[Math.floorMod(virtualIndex, Muscle.entries.size)]

    fun visible(current: Muscle): List<Muscle> = listOf(next(current, -1), current, next(current, 1))

    /** The closest virtual copy of [muscle] to [fromIndex]. */
    fun nearestEquivalentIndex(fromIndex: Int, muscle: Muscle): Int {
        val forward = Math.floorMod(Muscle.entries.indexOf(muscle) - Muscle.entries.indexOf(muscleAt(fromIndex)), Muscle.entries.size)
        val delta = if (forward > Muscle.entries.size / 2) forward - Muscle.entries.size else forward
        return fromIndex + delta
    }

    fun anchoredIndex(muscle: Muscle): Int = nearestEquivalentIndex(middleAnchor, muscle)

    fun shouldRecenter(virtualIndex: Int): Boolean =
        virtualIndex < Muscle.entries.size * 2 || virtualIndex > VIRTUAL_ITEM_COUNT - Muscle.entries.size * 3

    /** External input is deliberately deferred until a user gesture has fully settled. */
    fun canReconcileExternal(
        pendingExternal: Muscle?,
        settledMuscle: Muscle,
        userInteractionInProgress: Boolean,
    ): Boolean = pendingExternal != null && pendingExternal != settledMuscle && !userInteractionInProgress

    fun centeredIndex(items: List<VisibleItem>, viewportStart: Int, viewportEnd: Int): Int? {
        val center = (viewportStart + viewportEnd) / 2f
        return items.minByOrNull { abs((it.offset + it.size / 2f) - center) }?.index
    }

    /**
     * A read-only projection of an item's measured distance from the actual lazy viewport
     * centre. The geometry can be temporarily absent before first layout; in that case the
     * neutral profile leaves the slot fully readable until measured coordinates arrive.
     */
    fun focusProfile(
        item: VisibleItem,
        viewportStart: Int,
        viewportEnd: Int,
    ): FocusProfile {
        val halfViewport = (viewportEnd - viewportStart) / 2f
        if (item.size <= 0 || halfViewport <= 0f) return FocusProfile.Neutral

        val viewportCenter = (viewportStart + viewportEnd) / 2f
        val itemCenter = item.offset + item.size / 2f
        val focus = (1f - abs(itemCenter - viewportCenter) / halfViewport).coerceIn(0f, 1f)
        return FocusProfile(
            amount = focus,
            scale = MIN_FOCUS_SCALE + (1f - MIN_FOCUS_SCALE) * focus,
            alpha = MIN_FOCUS_ALPHA + (1f - MIN_FOCUS_ALPHA) * focus,
        )
    }

    /** Resolves a [virtualIndex] from the current visible lazy items without any UI side effect. */
    fun focusProfileFor(
        virtualIndex: Int,
        visibleItems: List<VisibleItem>,
        viewportStart: Int,
        viewportEnd: Int,
    ): FocusProfile = visibleItems.firstOrNull { it.index == virtualIndex }
        ?.let { focusProfile(it, viewportStart, viewportEnd) }
        ?: FocusProfile.Neutral

    internal data class VisibleItem(val index: Int, val offset: Int, val size: Int)

    internal data class FocusProfile(
        val amount: Float,
        val scale: Float,
        val alpha: Float,
    ) {
        companion object {
            val Neutral = FocusProfile(amount = 0f, scale = 1f, alpha = 1f)
        }
    }
}

/** Three equal text slots in a genuinely scrollable cyclic wheel. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun MuscleSelector(
    selected: Muscle?,
    roleText: ((Muscle) -> String)? = null,
    onSelected: (Muscle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = gymHaptics()
    val external = selected ?: Muscle.entries.first()
    // Deliberately not rememberSaveable: virtual wheel position has no durable meaning. A fresh
    // composition recreates this state from the externally restored logical muscle instead.
    val listState = remember { LazyListState(MuscleSelectorState.anchoredIndex(external) - 1) }
    val flingBehavior = rememberSnapFlingBehavior(listState)
    val scope = rememberCoroutineScope()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var settledMuscle by remember { mutableStateOf(external) }
    var userGesturePending by remember { mutableStateOf(false) }
    var programmaticScroll by remember { mutableStateOf<Job?>(null) }
    var pendingExternal by remember { mutableStateOf<Muscle?>(null) }

    fun centeredVirtualIndex(): Int? = listState.centeredVirtualIndex()

    fun emitUserSelection(muscle: Muscle) {
        if (muscle != settledMuscle) {
            settledMuscle = muscle
            haptics.tap()
            onSelected(muscle)
        }
    }

    fun requestUserSelection(muscle: Muscle) {
        programmaticScroll?.cancel()
        programmaticScroll = scope.launch {
            val from = centeredVirtualIndex() ?: MuscleSelectorState.anchoredIndex(settledMuscle)
            listState.animateScrollToItem(MuscleSelectorState.nearestEquivalentIndex(from, muscle) - 1)
            emitUserSelection(muscleAtCenterOrNull(listState) ?: muscle)
        }
    }

    // A real drag always wins over a queued external or accessibility animation.
    LaunchedEffect(dragged) {
        if (dragged) {
            programmaticScroll?.cancel()
            userGesturePending = true
        }
    }

    // Parent state is logical only. Coalesce it while a user drag/fling is active: otherwise an
    // external animation could steal the gesture before its actual centre has settled.
    LaunchedEffect(external) {
        if (external == settledMuscle) {
            pendingExternal = null
        } else {
            programmaticScroll?.cancel()
            pendingExternal = external
        }
    }

    LaunchedEffect(pendingExternal, dragged, listState.isScrollInProgress) {
        val target = pendingExternal ?: return@LaunchedEffect
        if (!MuscleSelectorState.canReconcileExternal(
                pendingExternal = target,
                settledMuscle = settledMuscle,
                userInteractionInProgress = dragged || listState.isScrollInProgress || userGesturePending,
            )
        ) return@LaunchedEffect
        if (target == settledMuscle) {
            pendingExternal = null
            return@LaunchedEffect
        }
        programmaticScroll = scope.launch {
            val from = centeredVirtualIndex() ?: MuscleSelectorState.anchoredIndex(target)
            listState.animateScrollToItem(MuscleSelectorState.nearestEquivalentIndex(from, target) - 1)
            if (!dragged && !userGesturePending && pendingExternal == target) {
                settledMuscle = target
                pendingExternal = null
            }
        }
    }

    // Snap has finished: one gesture settles to its actual centre, then an equivalent idle copy
    // is recentred before a user can practically reach either end of the virtual range.
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val centeredIndex = centeredVirtualIndex() ?: return@LaunchedEffect
            if (userGesturePending) {
                userGesturePending = false
                // A user-settled centre supersedes any external value that arrived mid-gesture.
                pendingExternal = null
                emitUserSelection(MuscleSelectorState.muscleAt(centeredIndex))
            }
            if (!dragged && !userGesturePending && MuscleSelectorState.shouldRecenter(centeredIndex)) {
                listState.scrollToItem(MuscleSelectorState.anchoredIndex(MuscleSelectorState.muscleAt(centeredIndex)) - 1)
            }
        }
    }

    Column(
        modifier = modifier.semantics {
            stateDescription = roleText?.invoke(settledMuscle)
                ?.let { "${settledMuscle.displayName()}: $it" }
                ?: settledMuscle.displayName()
            customActions = listOf(
                CustomAccessibilityAction("Предыдущая мышца") {
                    requestUserSelection(MuscleSelectorState.next(settledMuscle, -1))
                    true
                },
                CustomAccessibilityAction("Следующая мышца") {
                    requestUserSelection(MuscleSelectorState.next(settledMuscle, 1))
                    true
                },
            )
        }.testTag("muscle_selector"),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
        ) {
            val slotWidth = maxWidth / 3
            // Lazy layouts have no intrinsic measurement. This invisible, semantic-free row gives
            // the box its real wrapped height before the matching LazyRow is measured.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(0f)
                    .clearAndSetSemantics { },
            ) {
                SelectorSlot(
                    muscle = MuscleSelectorState.next(settledMuscle, -1),
                    onClick = null,
                    modifier = Modifier.weight(1f),
                )
                SelectorSlot(
                    muscle = settledMuscle,
                    onClick = null,
                    modifier = Modifier.weight(1f),
                    roleText = roleText?.invoke(settledMuscle),
                )
                SelectorSlot(
                    muscle = MuscleSelectorState.next(settledMuscle, 1),
                    onClick = null,
                    modifier = Modifier.weight(1f),
                )
            }
            LazyRow(
                state = listState,
                modifier = Modifier
                    .matchParentSize()
                    .testTag("muscle_selector_viewport"),
                flingBehavior = flingBehavior,
            ) {
                items(count = 1_000_001, key = { it }) { virtualIndex ->
                    val muscle = MuscleSelectorState.muscleAt(virtualIndex)
                    val slot = slotTagFor(muscle, settledMuscle)
                    val focusProfile = listState.focusProfileFor(virtualIndex)
                    SelectorSlot(
                        muscle = muscle,
                        onClick = when (slot) {
                            "muscle_selector_previous", "muscle_selector_next" -> { { requestUserSelection(muscle) } }
                            else -> null
                        },
                        modifier = Modifier.width(slotWidth).testTag(slot),
                        focusProfile = focusProfile,
                        textColor = lerp(
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            MaterialTheme.colorScheme.primary,
                            focusProfile.amount,
                        ),
                        roleText = if (slot == "muscle_selector_current") roleText?.invoke(muscle) else null,
                        selected = slot == "muscle_selector_current",
                    )
                }
            }
            // A match-parent overlay keeps the dividers full height without allowing their
            // fillMaxHeight modifier to define this wrap-content selector's height.
            Box(modifier = Modifier.matchParentSize()) {
                VerticalDivider(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = slotWidth)
                        .width(1.dp)
                        .fillMaxHeight()
                        .testTag("muscle_selector_divider_previous"),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                VerticalDivider(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = slotWidth * 2)
                        .width(1.dp)
                        .fillMaxHeight()
                        .testTag("muscle_selector_divider_next"),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

private fun slotTagFor(muscle: Muscle, settled: Muscle): String = when (muscle) {
    MuscleSelectorState.next(settled, -1) -> "muscle_selector_previous"
    settled -> "muscle_selector_current"
    MuscleSelectorState.next(settled, 1) -> "muscle_selector_next"
    else -> "muscle_selector_offscreen"
}

private fun LazyListState.centeredVirtualIndex(): Int? = MuscleSelectorState.centeredIndex(
    items = layoutInfo.visibleItemsInfo.map { item ->
        MuscleSelectorState.VisibleItem(item.index, item.offset, item.size)
    },
    viewportStart = layoutInfo.viewportStartOffset,
    viewportEnd = layoutInfo.viewportEndOffset,
)

private fun LazyListState.focusProfileFor(virtualIndex: Int): MuscleSelectorState.FocusProfile {
    val info = layoutInfo
    return MuscleSelectorState.focusProfileFor(
        virtualIndex = virtualIndex,
        visibleItems = info.visibleItemsInfo.map { item ->
            MuscleSelectorState.VisibleItem(item.index, item.offset, item.size)
        },
        viewportStart = info.viewportStartOffset,
        viewportEnd = info.viewportEndOffset,
    )
}

private fun muscleAtCenterOrNull(state: LazyListState): Muscle? =
    state.centeredVirtualIndex()?.let(MuscleSelectorState::muscleAt)

@Composable
private fun SelectorSlot(
    muscle: Muscle,
    onClick: (() -> Unit)?,
    modifier: Modifier,
    focusProfile: MuscleSelectorState.FocusProfile = MuscleSelectorState.FocusProfile.Neutral,
    textColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    roleText: String? = null,
    selected: Boolean = false,
) {
    val interaction = if (onClick == null) Modifier else Modifier
        .defaultMinSize(minHeight = 48.dp)
        .clickable(onClick = onClick)
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .then(interaction)
            .semantics {
                this.selected = selected
                if (onClick == null) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.graphicsLayer {
                scaleX = focusProfile.scale
                scaleY = focusProfile.scale
                alpha = focusProfile.alpha
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = muscle.displayName(),
                style = MaterialTheme.typography.labelLarge,
                color = textColor,
                textAlign = TextAlign.Center,
            )
            roleText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
