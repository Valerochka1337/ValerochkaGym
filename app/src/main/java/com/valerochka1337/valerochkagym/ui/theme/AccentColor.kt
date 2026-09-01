package com.valerochka1337.valerochkagym.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Историческая фирменная палитра и связанная с ней иконка лаунчера. Полные light/dark схемы
 * Compose определены отдельно в `StaticColorSchemes.kt`; эти четыре цвета сохраняются как
 * совместимые launcher-токены и ключ настройки. Каждому варианту соответствует свой
 * `activity-alias` в манифесте ([aliasName]), который включает
 * [com.valerochka1337.valerochkagym.data.appicon.AppIconManager].
 *
 * [primary], [light], [container] и [ink] больше не являются UI color scheme: экранный код
 * использует только `MaterialTheme.colorScheme`, а эти значения нужны уведомлениям и ресурсам
 * launcher alias.
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
