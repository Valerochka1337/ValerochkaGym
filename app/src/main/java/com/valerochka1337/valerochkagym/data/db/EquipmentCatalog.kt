package com.valerochka1337.valerochkagym.data.db

/** Stable built-in equipment vocabulary. IDs are part of local storage and Sheet snapshots. */
object EquipmentCatalog {
  data class Equipment(
      val id: String,
      val name: String,
      val group: String,
      val synonyms: Set<String> = emptySet(),
      val provides: Set<String> = setOf(id),
  )

  val entries: List<Equipment> = listOf(
      item("barbell", "Штанга", "Свободные веса", "гриф"),
      item("dumbbells", "Гантели", "Свободные веса"),
      item("kettlebell", "Гиря", "Свободные веса"),
      item("ez_bar", "EZ-гриф", "Свободные веса", "кривой гриф"),
      item("plates", "Диски", "Свободные веса", "блины"),
      item("rack", "Стойка / силовая рама", "Стойки", "рама"),
      item("flat_bench", "Горизонтальная скамья", "Скамьи", "плоская скамья"),
      item("incline_bench", "Наклонная скамья", "Скамьи"),
      Equipment("adjustable_bench", "Регулируемая скамья", "Скамьи", setOf("настраиваемая скамья"), setOf("adjustable_bench", "flat_bench", "incline_bench")),
      item("decline_bench", "Скамья с отрицательным наклоном", "Скамьи", "decline"),
      item("upper_pulley", "Верхний блок", "Тренажёры"),
      item("lower_pulley", "Нижний блок", "Тренажёры"),
      item("crossover", "Кроссовер", "Тренажёры"),
      item("pullup_bar", "Турник", "Опоры", "перекладина"),
      item("dip_bars", "Брусья", "Опоры"),
      item("low_bar", "Низкая перекладина", "Опоры", "австралийские подтягивания"),
      item("rings", "Кольца", "Опоры"),
      item("parallettes", "Паралетсы", "Опоры"),
      item("captains_chair", "Стойка для подъёма коленей", "Опоры", "капитанское кресло"),
      item("support", "Опора", "Опоры"),
      Equipment("nordic_bench", "Скамья для нордических сгибаний", "Специализированное", setOf("нордическая скамья"), setOf("nordic_bench", "ankle_anchor")),
      item("ankle_anchor", "Фиксатор стоп", "Специализированное", "анкеры"),
      item("hyperextension_bench", "Гиперэкстензия", "Специализированное"),
      item("reverse_hyper", "Тренажёр reverse hyper", "Специализированное"),
      item("t_bar_machine", "T-гриф", "Специализированное"),
      item("smith_machine", "Машина Смита", "Тренажёры"),
      item("hack_squat", "Гакк-тренажёр", "Тренажёры"),
      item("leg_press", "Жим ногами", "Тренажёры"),
      item("leg_extension", "Разгибатель ног", "Тренажёры"),
      item("leg_curl", "Сгибатель ног", "Тренажёры"),
      item("chest_press_machine", "Тренажёр для жима", "Тренажёры"),
      item("pec_deck", "Пек-дек", "Тренажёры"),
      item("shoulder_press_machine", "Тренажёр для жима плечами", "Тренажёры"),
      item("rear_delt_machine", "Тренажёр для задней дельты", "Тренажёры"),
      item("row_machine", "Тяговый тренажёр", "Тренажёры"),
      item("gravitron", "Гравитрон", "Тренажёры"),
      item("back_extension_machine", "Тренажёр для разгибаний спины", "Тренажёры"),
      item("hip_thrust_machine", "Тренажёр для хип-траста", "Тренажёры"),
      item("hip_abduction_machine", "Тренажёр для отведения бедра", "Тренажёры"),
      item("hip_adduction_machine", "Тренажёр для приведения бедра", "Тренажёры"),
      item("preacher_bench", "Скамья Скотта", "Скамьи"),
      item("trap_bar", "Трэп-гриф", "Свободные веса"),
      item("rowing_machine", "Гребной тренажёр", "Кардио"),
      item("treadmill", "Беговая дорожка", "Кардио"),
      item("exercise_bike", "Велотренажёр", "Кардио"),
      item("elliptical", "Эллипс", "Кардио", "орбитрек"),
      item("stair_climber", "Лестничный тренажёр", "Кардио"),
      item("stepper", "Степпер", "Кардио"),
      item("air_bike", "Air bike", "Кардио"),
      item("ski_erg", "SkiErg", "Кардио"),
      item("jump_rope", "Скакалка", "Кардио"),
      item("ab_wheel", "Колесо для пресса", "Аксессуары"),
      item("shrug_machine", "Тренажёр для шрагов", "Тренажёры", "трапеция"),
      item("box", "Тумба", "Специализированное"),
      item("seated_calf_machine", "Тренажёр для икр сидя", "Тренажёры"),
      item("standing_calf_machine", "Тренажёр для икр стоя", "Тренажёры"),
      item("platform", "Платформа", "Специализированное"),
      item("blocks", "Плинты", "Специализированное"),
      item("board", "Доска для жима", "Специализированное"),
      item("resistance_band", "Резиновая лента", "Аксессуары", "резинка"),
      item("pool", "Бассейн", "Кардио"),
  )

  private fun item(id: String, name: String, group: String, vararg synonyms: String) =
      Equipment(id, name, group, synonyms.toSet())

  private val byId = entries.associateBy(Equipment::id)
  fun require(id: String): Equipment = requireNotNull(byId[id]) { "Unknown equipment id: $id" }
  fun isKnown(id: String): Boolean = id in byId
  fun search(query: String): List<Equipment> {
    val needle = query.trim()
    return entries.filter { needle.isBlank() || sequenceOf(it.name, it.group).plus(it.synonyms.asSequence()).any { label -> label.contains(needle, true) } }
  }
  fun covers(selected: Set<String>, required: String): Boolean =
      selected.any { id -> byId[id]?.provides?.contains(required) == true }
}
