package com.valerochka1337.valerochkagym.data.db.entity

/**
 * Конкретная мышца (мышечная группа второго уровня) — единица учёта нагрузки в аналитике.
 *
 * [MuscleGroup] остаётся крупной категорией упражнения (фильтры библиотеки, колонка `muscle_group`
 * в выгрузке Sheets), а [Muscle] описывает, что именно упражнение нагружает: жим лёжа — это грудь +
 * трицепс + передняя дельта, а не «грудь». Карта вовлечения хранится в `exercise_muscles`
 * ([ExerciseMuscleEntity]) и питает тепловую карту тела и подсчёт эффективных подходов на мышцу.
 *
 * Порядок значений — анатомический (сверху вниз, спереди назад): он же порядок вывода в списках и
 * легенде.
 */
enum class Muscle {
  UPPER_CHEST,
  LOWER_CHEST,
  FRONT_DELTS,
  SIDE_DELTS,
  REAR_DELTS,
  ROTATOR_CUFF,
  SERRATUS_ANTERIOR,
  BICEPS,
  TRICEPS,
  FOREARMS,
  ABS,
  OBLIQUES,
  HIP_FLEXORS,
  ADDUCTORS,
  QUADS,
  TIBIALIS_ANTERIOR,
  CALVES,
  HAMSTRINGS,
  GLUTES,
  HIP_ABDUCTORS,
  LOWER_BACK,
  LATS,
  UPPER_BACK,
  TRAPS,
  NECK;

  companion object {
    /** Source-compatible alias for the old catalogue only; never a persisted v13 ID. */
    @Deprecated("Use UPPER_CHEST or LOWER_CHEST")
    val CHEST
      get() = UPPER_CHEST
  }
}

/** The only values persisted after v13: primary=100, secondary=50, stabilizer=0. */
enum class MuscleRole(val contribution: Int, val label: String) {
  PRIMARY(100, "Основная"),
  SECONDARY(50, "Вторичная"),
  STABILIZER(0, "Стабилизатор");

  companion object {
    fun fromContribution(value: Int): MuscleRole? = entries.firstOrNull { it.contribution == value }

    fun fromLegacyContribution(value: Int): MuscleRole? =
        when (value) {
          in 60..100 -> PRIMARY
          in 25..59 -> SECONDARY
          in 1..24 -> STABILIZER
          else -> null
        }
  }
}

/** No row is NOT_INVOLVED; a zero row is the explicit stabilizer role. */
data class MuscleLoad(
    val muscle: Muscle,
    val contribution: Int,
) {
  val role: MuscleRole?
    get() = MuscleRole.fromContribution(contribution)
}

/** Крупная категория, к которой относится мышца — для группировки в UI и фоллбэков. */
fun Muscle.group(): MuscleGroup =
    when (this) {
      Muscle.UPPER_CHEST,
      Muscle.LOWER_CHEST -> MuscleGroup.CHEST
      Muscle.FRONT_DELTS,
      Muscle.SIDE_DELTS,
      Muscle.REAR_DELTS,
      Muscle.ROTATOR_CUFF,
      Muscle.SERRATUS_ANTERIOR -> MuscleGroup.SHOULDERS
      Muscle.TRAPS,
      Muscle.LATS,
      Muscle.UPPER_BACK,
      Muscle.LOWER_BACK,
      Muscle.NECK -> MuscleGroup.BACK
      Muscle.BICEPS,
      Muscle.TRICEPS,
      Muscle.FOREARMS -> MuscleGroup.ARMS
      Muscle.ABS,
      Muscle.OBLIQUES -> MuscleGroup.CORE
      Muscle.GLUTES,
      Muscle.QUADS,
      Muscle.HAMSTRINGS,
      Muscle.ADDUCTORS,
      Muscle.CALVES,
      Muscle.HIP_FLEXORS,
      Muscle.HIP_ABDUCTORS,
      Muscle.TIBIALIS_ANTERIOR -> MuscleGroup.LEGS
    }
