package com.valerochka1337.valerochkagym.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import com.valerochka1337.valerochkagym.ui.theme.GymBlack
import com.valerochka1337.valerochkagym.ui.theme.GymGreen
import com.valerochka1337.valerochkagym.ui.theme.Peach

/**
 * Fills the screen with the near-black base and paints two soft radial glow spots
 * beneath [content]: a green one in the top-right and a peach one in the bottom-left.
 */
@Composable
fun GlowBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GymBlack)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(GymGreen.copy(alpha = 0.30f), GymGreen.copy(alpha = 0f)),
                        center = Offset(size.width * 0.95f, size.height * 0.05f),
                        radius = size.maxDimension * 0.6f,
                    ),
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Peach.copy(alpha = 0.18f), Peach.copy(alpha = 0f)),
                        center = Offset(size.width * 0.05f, size.height * 0.95f),
                        radius = size.maxDimension * 0.55f,
                    ),
                )
            },
    ) {
        content()
    }
}
