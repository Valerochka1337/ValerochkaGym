package com.valerochka1337.valerochkagym.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Акцент приложения — тот самый «единственный цвет» из `docs/design-system.md`, но выбираемый
 * пользователем. Один вариант задаёт сразу и схему Compose ([GymTheme]), и иконку в лаунчере:
 * каждому акценту соответствует свой `activity-alias` в манифесте ([aliasName]), который
 * включает [com.valerochka1337.valerochkagym.data.appicon.AppIconManager].
 *
 * Четыре тона на вариант — ровно те роли, которые схема красит акцентом:
 * [primary] (кнопки, активные состояния), [light] (контент на акцентном контейнере),
 * [container] (мягкий акцентный блок) и [ink] (тёмный текст/иконка поверх яркой заливки).
 * Тона подобраны вручную: у каждого оттенка своя воспринимаемая яркость, и вычислять их
 * сдвигом одной формулы значило бы получить блёклый лайм или нечитаемый коралл.
 *
 * [id] — ключ хранения в DataStore, менять нельзя: по нему настройка читается после обновления.
 */
enum class AccentColor(
    val id: String,
    val label: String,
    val primary: Color,
    val light: Color,
    val container: Color,
    val ink: Color,
    val aliasName: String,
) {
    GREEN(
        id = "green",
        label = "Зелёный",
        primary = Color(0xFF3DDC84),
        light = Color(0xFF6FE9A6),
        container = Color(0xFF123A24),
        ink = Color(0xFF05130B),
        aliasName = "MainActivityGreen",
    ),
    LIME(
        id = "lime",
        label = "Лаймовый",
        primary = Color(0xFFC6F24E),
        light = Color(0xFFDAF888),
        container = Color(0xFF2C3A11),
        ink = Color(0xFF0F1305),
        aliasName = "MainActivityLime",
    ),
    CYAN(
        id = "cyan",
        label = "Голубой",
        primary = Color(0xFF4CD3F5),
        light = Color(0xFF8AE3F8),
        container = Color(0xFF10353F),
        ink = Color(0xFF03131A),
        aliasName = "MainActivityCyan",
    ),
    CORAL(
        id = "coral",
        label = "Коралловый",
        primary = Color(0xFFE9603F),
        light = Color(0xFFF2907A),
        container = Color(0xFF43180E),
        ink = Color(0xFF1A0603),
        aliasName = "MainActivityCoral",
    ),
    ;

    companion object {

        /** Исторический акцент приложения: он же иконка, включённая в манифесте по умолчанию. */
        val DEFAULT: AccentColor = GREEN

        /** Разбирает сохранённый [id]; неизвестное или отсутствующее значение — [DEFAULT]. */
        fun fromId(id: String?): AccentColor = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
