package com.valerochka1337.valerochkagym.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableCollectionItemScope

/**
 * Единственная сенсорная зона начала reorder: 48dp grip с немедленным началом drag.
 * Действия «Переместить выше/ниже» живут на карточке, поэтому сам grip не получает фокус TalkBack.
 */
@Composable
fun DragHandle(
    reorderableItemScope: ReorderableCollectionItemScope,
    onDragStarted: () -> Unit,
    onDragStopped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = {},
        modifier = with(reorderableItemScope) {
            modifier
                .size(48.dp)
                .draggableHandle(
                    onDragStarted = { onDragStarted() },
                    onDragStopped = onDragStopped,
                )
                .clearAndSetSemantics {}
        },
    ) {
        Icon(
            imageVector = Icons.Rounded.DragHandle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
