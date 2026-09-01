package com.valerochka1337.valerochkagym.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

private val MaxScreenContentWidth = 960.dp

/**
 * Fills the screen with the flat neutral background beneath [content]. Kept as a thin
 * wrapper (formerly painted radial glows) so screens need no structural change.
 */
@Composable
fun GlowBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = MaxScreenContentWidth)
                .fillMaxWidth()
                .fillMaxHeight()
                .align(Alignment.TopCenter),
        ) {
            content()
        }
    }
}
