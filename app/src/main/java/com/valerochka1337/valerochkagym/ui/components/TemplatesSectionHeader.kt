package com.valerochka1337.valerochkagym.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Accessible expandable heading used for the collapsed standard catalog in list screens. */
@Composable
fun TemplatesSectionHeader(
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  GymCard(
      modifier =
          modifier.fillMaxWidth().semantics {
            role = Role.Button
            contentDescription = "Встроенные, $count"
            stateDescription = if (expanded) "Встроенные раскрыты" else "Встроенные свернуты"
          },
      onClick = onClick,
      contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
          text = "Встроенные",
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
          text = count.toString(),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Icon(
          imageVector =
              if (expanded) Icons.Rounded.KeyboardArrowDown
              else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
