package com.valerochka1337.valerochkagym.data.db

import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad

/**
 * Фоллбэк-карта вовлечения по крупной группе мышц: используется, когда точной карты нет — для своих
 * упражнений, созданных без разметки по телу, и для упражнений, приехавших импортом из Google
 * Sheets (там есть только колонка `muscle_group`).
 *
 * Числа — «типичный представитель» группы (жим лёжа для груди, тяга для спины, приседания для ног).
 * Шкала глобальная: кардио и упражнения на выносливость не получают искусственные 100%. Точные
 * карты встроенного каталога живут в [seedExerciseMuscles].
 */
fun MuscleGroup.defaultMuscleLoads(): List<MuscleLoad> =
    when (this) {
      MuscleGroup.CHEST ->
          listOf(
              MuscleLoad(Muscle.UPPER_CHEST, 100),
              MuscleLoad(Muscle.LOWER_CHEST, 100),
              MuscleLoad(Muscle.TRICEPS, 50),
              MuscleLoad(Muscle.FRONT_DELTS, 50),
          )

      MuscleGroup.BACK ->
          listOf(
              MuscleLoad(Muscle.LATS, 100),
              MuscleLoad(Muscle.UPPER_BACK, 100),
              MuscleLoad(Muscle.BICEPS, 50),
              MuscleLoad(Muscle.REAR_DELTS, 50),
              MuscleLoad(Muscle.FOREARMS, 50),
              MuscleLoad(Muscle.LOWER_BACK, 50),
          )

      MuscleGroup.LEGS ->
          listOf(
              MuscleLoad(Muscle.QUADS, 100),
              MuscleLoad(Muscle.GLUTES, 100),
              MuscleLoad(Muscle.HAMSTRINGS, 50),
              MuscleLoad(Muscle.ADDUCTORS, 50),
              MuscleLoad(Muscle.LOWER_BACK, 50),
              MuscleLoad(Muscle.CALVES, 0),
          )

      MuscleGroup.SHOULDERS ->
          listOf(
              MuscleLoad(Muscle.FRONT_DELTS, 100),
              MuscleLoad(Muscle.SIDE_DELTS, 100),
              MuscleLoad(Muscle.TRICEPS, 50),
              MuscleLoad(Muscle.TRAPS, 50),
              MuscleLoad(Muscle.REAR_DELTS, 50),
          )

      MuscleGroup.ARMS ->
          listOf(
              MuscleLoad(Muscle.BICEPS, 100),
              MuscleLoad(Muscle.TRICEPS, 100),
              MuscleLoad(Muscle.FOREARMS, 50),
          )

      MuscleGroup.CORE ->
          listOf(
              MuscleLoad(Muscle.ABS, 100),
              MuscleLoad(Muscle.OBLIQUES, 50),
              MuscleLoad(Muscle.LOWER_BACK, 0),
          )

      MuscleGroup.CARDIO ->
          listOf(
              MuscleLoad(Muscle.QUADS, 50),
              MuscleLoad(Muscle.CALVES, 50),
              MuscleLoad(Muscle.HAMSTRINGS, 50),
              MuscleLoad(Muscle.GLUTES, 50),
          )

      MuscleGroup.FULL_BODY ->
          listOf(
              MuscleLoad(Muscle.QUADS, 100),
              MuscleLoad(Muscle.GLUTES, 100),
              MuscleLoad(Muscle.ABS, 50),
              MuscleLoad(Muscle.HAMSTRINGS, 50),
              MuscleLoad(Muscle.FRONT_DELTS, 50),
              MuscleLoad(Muscle.LATS, 50),
              MuscleLoad(Muscle.CALVES, 50),
              MuscleLoad(Muscle.TRICEPS, 50),
          )
    }
