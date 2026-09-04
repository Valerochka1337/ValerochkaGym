package com.valerochka1337.valerochkagym.ui.theme

/** Режим яркости приложения. SYSTEM следует текущей конфигурации Android. */
enum class ThemeMode(
    val id: String,
    val label: String,
) {
  SYSTEM(id = "system", label = "Системная"),
  LIGHT(id = "light", label = "Светлая"),
  DARK(id = "dark", label = "Тёмная"),
  ;

  companion object {
    fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM
  }
}
