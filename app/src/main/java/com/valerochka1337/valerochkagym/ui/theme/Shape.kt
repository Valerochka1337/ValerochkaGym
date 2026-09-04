package com.valerochka1337.valerochkagym.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Unified, symmetric rounded-corner system. Replaces the old asymmetric card shape — a single
 * consistent radius language (small chips/fields → large cards/sheets).
 */
val GymShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )
