package com.valerochka1337.valerochkagym.ui.navigation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Small project-owned policy; it intentionally avoids another adaptive dependency. */
enum class GymWindowWidthClass {
  Compact,
  Medium,
  Expanded;

  companion object {
    fun from(availableWidth: Dp): GymWindowWidthClass =
        when {
          availableWidth < 600.dp -> Compact
          availableWidth < 840.dp -> Medium
          else -> Expanded
        }
  }
}
