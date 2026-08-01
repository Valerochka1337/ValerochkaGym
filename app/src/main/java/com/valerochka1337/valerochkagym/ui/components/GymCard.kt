package com.valerochka1337.valerochkagym.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Default card shape — a single symmetric large radius, shared with border usages. */
val GymCardShape = RoundedCornerShape(24.dp)

/**
 * Flat surface card. Depth is conveyed by the tonal [surfaceContainerHigh] fill, not a
 * gradient. When [onClick] is non-null the card is clickable and shows a ripple clipped
 * to [shape].
 */
@Composable
fun GymCard(
    modifier: Modifier = Modifier,
    shape: Shape = GymCardShape,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(),
            role = Role.Button,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(clickModifier)
            .padding(contentPadding),
        content = content,
    )
}
