package com.valerochka1337.valerochkagym.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val defaults = Typography()

val GymTypography =
    Typography(
        headlineLarge =
            defaults.headlineLarge.copy(
                fontSize = 28.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
            ),
        headlineMedium =
            defaults.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.25).sp,
            ),
        headlineSmall =
            defaults.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
            ),
        titleLarge =
            defaults.titleLarge.copy(
                fontWeight = FontWeight.SemiBold,
            ),
    )
