package com.valerochka1337.valerochkagym.ui.haptics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Семантическая обёртка тактильного отклика: экраны зовут «что произошло»
 * ([confirm], [step]…), а не низкоуровневый [HapticFeedbackType]. Единая карта
 * «событие → паттерн» держит отклик согласованным по всему приложению, а настройка
 * `hapticsEnabled` выключает его в одном месте (все методы становятся no-op).
 *
 * Экраны получают экземпляр через [LocalGymHaptics]; корень приложения кладёт его в
 * композицию из настроек (см. MainActivity).
 */
class GymHaptics internal constructor(
    private val haptics: HapticFeedback,
    private val enabled: Boolean,
) {

    /** Лёгкий тап: выбор вкладки/карточки/мышцы. */
    fun tap() = perform(HapticFeedbackType.ContextClick)

    /** Подтверждение действия: подход выполнен. */
    fun confirm() = perform(HapticFeedbackType.Confirm)

    /** Успех-акцент: тренировка завершена, новый рекорд. */
    fun success() = perform(HapticFeedbackType.Confirm)

    /** Отклонение/деструктив: discard, удаление. */
    fun reject() = perform(HapticFeedbackType.Reject)

    /** Переключатель: снятие отметки, тумблеры. */
    fun toggle(on: Boolean) =
        perform(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

    /** Дискретный шаг значения: кнопки ± веса/повторов/отдыха. */
    fun step() = perform(HapticFeedbackType.SegmentTick)

    /** Частый мелкий шаг (long-press или crossing позиции при переносе) — тише обычного шага. */
    fun stepFrequent() = perform(HapticFeedbackType.SegmentFrequentTick)

    /** Захват упражнения для перетаскивания. */
    fun dragStart() = perform(HapticFeedbackType.GestureThresholdActivate)

    /** Отпускание упражнения после перетаскивания. */
    fun dragEnd() = perform(HapticFeedbackType.GestureEnd)

    /** Долгое нажатие распознано (меню, альтернативный шаг). */
    fun longPress() = perform(HapticFeedbackType.LongPress)

    private fun perform(type: HapticFeedbackType) {
        if (enabled) haptics.performHapticFeedback(type)
    }
}

/** Хаптика текущей композиции; по умолчанию — выключенная заглушка до прокидывания из корня. */
val LocalGymHaptics = staticCompositionLocalOf<GymHaptics?> { null }

/**
 * Экземпляр [GymHaptics] поверх системного [LocalHapticFeedback]. Зовётся один раз в корне
 * (MainActivity) с актуальным значением настройки.
 */
@Composable
fun rememberGymHaptics(enabled: Boolean): GymHaptics {
    val systemHaptics = LocalHapticFeedback.current
    return remember(systemHaptics, enabled) { GymHaptics(systemHaptics, enabled) }
}

/** Хаптика экрана: удобный не-null доступ (заглушка no-op, если корень ничего не положил). */
@Composable
fun gymHaptics(): GymHaptics =
    LocalGymHaptics.current ?: rememberGymHaptics(enabled = false)
