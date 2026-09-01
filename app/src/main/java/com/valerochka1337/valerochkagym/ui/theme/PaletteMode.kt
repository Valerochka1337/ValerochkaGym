package com.valerochka1337.valerochkagym.ui.theme

/** Источник цветовой палитры. SYSTEM использует Material You, остальные — фирменную схему. */
enum class PaletteMode(
    val id: String,
    val label: String,
    val accent: AccentColor?,
) {
    SYSTEM(id = "system", label = "Системная", accent = null),
    GREEN(id = AccentColor.GREEN.id, label = AccentColor.GREEN.label, accent = AccentColor.GREEN),
    LIME(id = AccentColor.LIME.id, label = AccentColor.LIME.label, accent = AccentColor.LIME),
    CYAN(id = AccentColor.CYAN.id, label = AccentColor.CYAN.label, accent = AccentColor.CYAN),
    CORAL(id = AccentColor.CORAL.id, label = AccentColor.CORAL.label, accent = AccentColor.CORAL),
    ;

    companion object {
        fun fromId(id: String?): PaletteMode = entries.firstOrNull { it.id == id } ?: SYSTEM

        fun fromAccent(accent: AccentColor): PaletteMode =
            entries.first { it.accent == accent }
    }
}
