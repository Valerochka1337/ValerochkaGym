package com.valerochka1337.valerochkagym.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Круглая кнопка-иконка: иконка на заполненной круглой подложке (`surfaceContainerHigh`, как у
 * плавающего навбара). Используется для действий в шапке экрана (например «Настройки»).
 */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
  FilledTonalIconButton(
      onClick = onClick,
      modifier = modifier.size(48.dp),
      colors =
          IconButtonDefaults.filledTonalIconButtonColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
              contentColor = tint,
          ),
  ) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
    )
  }
}
