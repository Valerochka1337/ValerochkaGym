package com.valerochka1337.valerochkagym.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.valerochka1337.valerochkagym.ui.theme.GymMotion

/**
 * Разовый fade-in контента после загрузки: список/пустое состояние проявляются, а не
 * «выскакивают» кадром. Запускается один раз при входе в композицию — не мигает при
 * последующих обновлениях данных.
 */
@Composable
fun FadeInContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val appear = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = appear,
        enter = fadeIn(GymMotion.effectsDefault()),
        modifier = modifier,
    ) {
        content()
    }
}
