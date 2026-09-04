package com.valerochka1337.valerochkagym.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Default card shape — a single symmetric large radius, shared with border usages. */
val GymCardShape = RoundedCornerShape(24.dp)

/**
 * Flat surface card. Depth is conveyed by the tonal `surfaceContainerHigh` fill, not a gradient.
 * When [onClick] is non-null the card is clickable and shows a ripple clipped to [shape].
 */
@Composable
fun GymCard(
    modifier: Modifier = Modifier,
    shape: Shape = GymCardShape,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
  val colors =
      CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
          contentColor = MaterialTheme.colorScheme.onSurface,
      )
  if (onClick != null) {
    Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors) {
      Column(modifier = Modifier.padding(contentPadding), content = content)
    }
  } else {
    Card(modifier = modifier, shape = shape, colors = colors) {
      Column(modifier = Modifier.padding(contentPadding), content = content)
    }
  }
}
