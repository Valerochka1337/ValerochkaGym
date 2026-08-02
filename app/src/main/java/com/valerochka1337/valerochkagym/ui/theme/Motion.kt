package com.valerochka1337.valerochkagym.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset

/**
 * Единая точка моторики приложения. Всё движение — пружины Material 3 Expressive
 * ([MaterialTheme.motionScheme]); сторонние анимационные библиотеки не подключаем
 * (см. docs/design-system.md §7).
 *
 * Два вида токенов:
 *  * composable-аксессоры ([spatialFast] и т.п.) — тонкая типизированная прослойка над
 *    `motionScheme`, чтобы экраны не тянули экспериментальный API и не изобретали свои спеки;
 *  * статические спеки навигации ([NavSlideSpec], [NavFadeSpec], [TabFadeSpec]) — enter/exit
 *    лямбды `NavHost` не @Composable, до `MaterialTheme` оттуда не дотянуться, поэтому спеки
 *    заданы константами с теми же характеристиками, что пружины Expressive.
 *
 * spatial — для движения и размеров (слайды, масштаб, высота), effects — для «бесформенных»
 * свойств (цвет, прозрачность): им дотяжка-овершут не положена.
 */
object GymMotion {

    /** Быстрая пружина движения/размера — мелкие элементы: чипы, бейджи, переключатели. */
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    fun <T> spatialFast(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.fastSpatialSpec()

    /** Пружина движения/размера по умолчанию — карточки, секции, появления контента. */
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    fun <T> spatialDefault(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultSpatialSpec()

    /** Медленная пружина движения/размера — крупные полноэкранные перестроения. */
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    fun <T> spatialSlow(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.slowSpatialSpec()

    /** Быстрая пружина эффектов (цвет/альфа) — выделения, статусы. */
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    fun <T> effectsFast(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.fastEffectsSpec()

    /** Пружина эффектов по умолчанию — fade появления, кроссфейды содержимого. */
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    fun <T> effectsDefault(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultEffectsSpec()

    // --- Статические спеки навигации (использовать ТОЛЬКО в GymNavGraph) ---

    /**
     * Сдвиг экрана при push/pop и выезде модалок. Сдвиг и затухание живут в ОДНОМ тайминге,
     * иначе контент успевает стать непрозрачным, пока экран ещё едет, — читается как «тормозит».
     * StiffnessMedium (~300 мс) вместо MediumLow (~450 мс) делает переход снапнутее.
     */
    val NavSlideSpec: FiniteAnimationSpec<IntOffset> =
        spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)

    /** Затухание, парное [NavSlideSpec] — те же пружинные параметры. */
    val NavFadeSpec: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)

    /**
     * Кроссфейд между вкладками нижнего меню — быстрый, предсказуемый, без слайда.
     * Единственный tween в приложении: сиблингам направление не положено (§7 дизайн-системы).
     */
    val TabFadeSpec: FiniteAnimationSpec<Float> = tween(durationMillis = 180)
}
