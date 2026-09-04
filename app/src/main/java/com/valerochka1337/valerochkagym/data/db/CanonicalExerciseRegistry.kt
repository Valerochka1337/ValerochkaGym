package com.valerochka1337.valerochkagym.data.db

import com.valerochka1337.valerochkagym.data.db.entity.*
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Explicit reviewable rows; parsing never expands families or infers movement patterns. */
object CanonicalExerciseRegistry {
  enum class MovementPattern {
    CHEST_PRESS,
    CHEST_FLY,
    VERTICAL_PULL,
    HORIZONTAL_PULL,
    FACE_PULL,
    CURL,
    EXTENSION,
    FOREARM,
    SHOULDER_PRESS,
    LATERAL_RAISE,
    FRONT_RAISE,
    REAR_DELT_FLY,
    ROTATOR_CUFF,
    UPRIGHT_ROW,
    SHRUG,
    SQUAT,
    LEG_PRESS,
    LUNGE,
    HIP_HINGE,
    HIP_THRUST,
    LEG_EXTENSION,
    LEG_CURL,
    CALF_RAISE,
    TIBIALIS_RAISE,
    HIP_ABDUCTION,
    HIP_ADDUCTION,
    BACK_EXTENSION,
    CORE,
    CORE_FLEXION,
    CORE_ANTI_ROTATION,
    CORE_ROTATION,
    CARDIO,
    FULL_BODY,
  }

  data class Entry(
      val key: String,
      val exercise: ExerciseEntity,
      val movementPattern: MovementPattern,
      val loads: List<MuscleLoad>,
      val coverage: Set<String>,
      val legacySyncIds: Set<String> = emptySet(),
      val legacyNames: Set<String> = emptySet(),
  )

  private fun id(key: String) =
      UUID.nameUUIDFromBytes(
              "ValerochkaGym.canonical-exercise:$key".toByteArray(StandardCharsets.UTF_8)
          )
          .toString()

  fun canonicalLoads(p: MovementPattern) =
      when (p) {
        MovementPattern.CHEST_PRESS ->
            listOf(
                MuscleLoad(Muscle.UPPER_CHEST, 100),
                MuscleLoad(Muscle.LOWER_CHEST, 100),
                MuscleLoad(Muscle.TRICEPS, 50),
                MuscleLoad(Muscle.FRONT_DELTS, 50),
                MuscleLoad(Muscle.SERRATUS_ANTERIOR, 0),
            )
        MovementPattern.CHEST_FLY ->
            listOf(
                MuscleLoad(Muscle.UPPER_CHEST, 100),
                MuscleLoad(Muscle.LOWER_CHEST, 100),
                MuscleLoad(Muscle.FRONT_DELTS, 50),
            )
        MovementPattern.VERTICAL_PULL,
        MovementPattern.HORIZONTAL_PULL ->
            listOf(
                MuscleLoad(Muscle.LATS, 100),
                MuscleLoad(Muscle.UPPER_BACK, 100),
                MuscleLoad(Muscle.BICEPS, 50),
                MuscleLoad(Muscle.REAR_DELTS, 50),
            )
        MovementPattern.FACE_PULL ->
            listOf(
                MuscleLoad(Muscle.REAR_DELTS, 100),
                MuscleLoad(Muscle.UPPER_BACK, 100),
                MuscleLoad(Muscle.ROTATOR_CUFF, 50),
                MuscleLoad(Muscle.TRAPS, 50),
            )
        MovementPattern.CURL ->
            listOf(MuscleLoad(Muscle.BICEPS, 100), MuscleLoad(Muscle.FOREARMS, 50))
        MovementPattern.EXTENSION ->
            listOf(MuscleLoad(Muscle.TRICEPS, 100), MuscleLoad(Muscle.FRONT_DELTS, 50))
        MovementPattern.SHOULDER_PRESS ->
            listOf(
                MuscleLoad(Muscle.FRONT_DELTS, 100),
                MuscleLoad(Muscle.SIDE_DELTS, 100),
                MuscleLoad(Muscle.TRICEPS, 50),
                MuscleLoad(Muscle.ROTATOR_CUFF, 0),
            )
        MovementPattern.LATERAL_RAISE ->
            listOf(MuscleLoad(Muscle.SIDE_DELTS, 100), MuscleLoad(Muscle.ROTATOR_CUFF, 50))
        MovementPattern.ROTATOR_CUFF ->
            listOf(MuscleLoad(Muscle.ROTATOR_CUFF, 100), MuscleLoad(Muscle.REAR_DELTS, 50))
        MovementPattern.SHRUG ->
            listOf(MuscleLoad(Muscle.TRAPS, 100), MuscleLoad(Muscle.FOREARMS, 50))
        MovementPattern.SQUAT ->
            listOf(
                MuscleLoad(Muscle.QUADS, 100),
                MuscleLoad(Muscle.GLUTES, 100),
                MuscleLoad(Muscle.HAMSTRINGS, 50),
            )
        MovementPattern.HIP_HINGE ->
            listOf(
                MuscleLoad(Muscle.GLUTES, 100),
                MuscleLoad(Muscle.HAMSTRINGS, 100),
                MuscleLoad(Muscle.LOWER_BACK, 50),
            )
        MovementPattern.CORE -> listOf(MuscleLoad(Muscle.ABS, 100), MuscleLoad(Muscle.OBLIQUES, 50))
        MovementPattern.CARDIO ->
            listOf(
                MuscleLoad(Muscle.QUADS, 50),
                MuscleLoad(Muscle.HAMSTRINGS, 50),
                MuscleLoad(Muscle.GLUTES, 50),
                MuscleLoad(Muscle.CALVES, 50),
            )
        MovementPattern.FOREARM ->
            listOf(MuscleLoad(Muscle.FOREARMS, 100), MuscleLoad(Muscle.BICEPS, 50))
        MovementPattern.FRONT_RAISE ->
            listOf(MuscleLoad(Muscle.FRONT_DELTS, 100), MuscleLoad(Muscle.SERRATUS_ANTERIOR, 50))
        MovementPattern.REAR_DELT_FLY ->
            listOf(
                MuscleLoad(Muscle.REAR_DELTS, 100),
                MuscleLoad(Muscle.UPPER_BACK, 50),
                MuscleLoad(Muscle.ROTATOR_CUFF, 50),
            )
        MovementPattern.UPRIGHT_ROW ->
            listOf(MuscleLoad(Muscle.SIDE_DELTS, 100), MuscleLoad(Muscle.TRAPS, 50))
        MovementPattern.LEG_PRESS ->
            listOf(
                MuscleLoad(Muscle.QUADS, 100),
                MuscleLoad(Muscle.GLUTES, 100),
                MuscleLoad(Muscle.HAMSTRINGS, 50),
            )
        MovementPattern.LUNGE ->
            listOf(
                MuscleLoad(Muscle.QUADS, 100),
                MuscleLoad(Muscle.GLUTES, 100),
                MuscleLoad(Muscle.HAMSTRINGS, 50),
            )
        MovementPattern.HIP_THRUST ->
            listOf(MuscleLoad(Muscle.GLUTES, 100), MuscleLoad(Muscle.HAMSTRINGS, 50))
        MovementPattern.LEG_EXTENSION -> listOf(MuscleLoad(Muscle.QUADS, 100))
        MovementPattern.LEG_CURL ->
            listOf(MuscleLoad(Muscle.HAMSTRINGS, 100), MuscleLoad(Muscle.CALVES, 50))
        MovementPattern.CALF_RAISE ->
            listOf(MuscleLoad(Muscle.CALVES, 100), MuscleLoad(Muscle.TIBIALIS_ANTERIOR, 50))
        MovementPattern.TIBIALIS_RAISE ->
            listOf(MuscleLoad(Muscle.TIBIALIS_ANTERIOR, 100), MuscleLoad(Muscle.CALVES, 50))
        MovementPattern.HIP_ABDUCTION ->
            listOf(MuscleLoad(Muscle.HIP_ABDUCTORS, 100), MuscleLoad(Muscle.GLUTES, 50))
        MovementPattern.HIP_ADDUCTION ->
            listOf(MuscleLoad(Muscle.ADDUCTORS, 100), MuscleLoad(Muscle.QUADS, 50))
        MovementPattern.BACK_EXTENSION ->
            listOf(
                MuscleLoad(Muscle.LOWER_BACK, 100),
                MuscleLoad(Muscle.GLUTES, 50),
                MuscleLoad(Muscle.HAMSTRINGS, 50),
            )
        MovementPattern.CORE_FLEXION ->
            listOf(
                MuscleLoad(Muscle.ABS, 100),
                MuscleLoad(Muscle.HIP_FLEXORS, 50),
                MuscleLoad(Muscle.OBLIQUES, 50),
            )
        MovementPattern.CORE_ANTI_ROTATION ->
            listOf(
                MuscleLoad(Muscle.ABS, 100),
                MuscleLoad(Muscle.OBLIQUES, 100),
                MuscleLoad(Muscle.HIP_FLEXORS, 50),
            )
        MovementPattern.CORE_ROTATION ->
            listOf(
                MuscleLoad(Muscle.OBLIQUES, 100),
                MuscleLoad(Muscle.ABS, 50),
                MuscleLoad(Muscle.HIP_FLEXORS, 50),
            )
        MovementPattern.FULL_BODY ->
            listOf(
                MuscleLoad(Muscle.QUADS, 100),
                MuscleLoad(Muscle.GLUTES, 100),
                MuscleLoad(Muscle.UPPER_CHEST, 50),
                MuscleLoad(Muscle.LATS, 50),
                MuscleLoad(Muscle.ABS, 50),
            )
      }

  private fun parse(r: String): Entry {
    val f = r.split('|')
    require(f.size == 8)
    val legacy = f[7].takeUnless { it == "-" }
    val p = MovementPattern.valueOf(f[6])
    val syncId = legacy?.let(::builtInExerciseSyncId) ?: id(f[0])
    return Entry(
        f[0],
        ExerciseEntity(
            name = f[1],
            muscleGroup = MuscleGroup.valueOf(f[2]),
            type = ExerciseType.valueOf(f[3]),
            isCustom = false,
            syncId = syncId,
            updatedAt = 13,
        ),
        p,
        canonicalLoads(p),
        setOf(f[4], f[5]),
        legacy?.let { setOf(builtInExerciseSyncId(it)) }.orEmpty(),
        legacy?.let(::setOf).orEmpty(),
    )
  }

  private val registryRows =
      """
legacy-1|Жим штанги лёжа|CHEST|STRENGTH|barbell|legacy|CHEST_PRESS|Жим штанги лёжа
legacy-2|Жим гантелей лёжа|CHEST|STRENGTH|dumbbell|legacy|CHEST_PRESS|Жим гантелей лёжа
legacy-3|Жим штанги на наклонной скамье|CHEST|STRENGTH|barbell|legacy|CHEST_PRESS|Жим штанги на наклонной скамье
legacy-4|Жим гантелей на наклонной скамье|CHEST|STRENGTH|dumbbell|legacy|CHEST_PRESS|Жим гантелей на наклонной скамье
legacy-5|Разводка гантелей лёжа|CHEST|STRENGTH|dumbbell|legacy|CHEST_FLY|Разводка гантелей лёжа
legacy-6|Сведение гантелей на наклонной скамье|CHEST|STRENGTH|dumbbell|legacy|CHEST_FLY|Сведение гантелей на наклонной скамье
legacy-7|Отжимания от пола|CHEST|STRENGTH|bodyweight|legacy|CHEST_PRESS|Отжимания от пола
legacy-8|Сведение рук в кроссовере|CHEST|STRENGTH|cable|legacy|CHEST_FLY|Сведение рук в кроссовере
legacy-9|Жим в тренажёре Хаммер|CHEST|STRENGTH|machine|legacy|CHEST_PRESS|Жим в тренажёре Хаммер
legacy-10|Пуловер с гантелью|CHEST|STRENGTH|dumbbell|legacy|CHEST_FLY|Пуловер с гантелью
legacy-11|Подтягивания|BACK|STRENGTH|bodyweight|legacy|VERTICAL_PULL|Подтягивания
legacy-12|Подтягивания обратным хватом|BACK|STRENGTH|bodyweight|legacy|VERTICAL_PULL|Подтягивания обратным хватом
legacy-13|Тяга штанги в наклоне|BACK|STRENGTH|barbell|legacy|HORIZONTAL_PULL|Тяга штанги в наклоне
legacy-14|Тяга верхнего блока|BACK|STRENGTH|cable|legacy|VERTICAL_PULL|Тяга верхнего блока
legacy-15|Тяга нижнего блока|BACK|STRENGTH|cable|legacy|HORIZONTAL_PULL|Тяга нижнего блока
legacy-16|Тяга гантели одной рукой|BACK|STRENGTH|dumbbell|legacy|HORIZONTAL_PULL|Тяга гантели одной рукой
legacy-17|Становая тяга|BACK|STRENGTH|barbell|legacy|HIP_HINGE|Становая тяга
legacy-18|Тяга Т-грифа|BACK|STRENGTH|barbell|legacy|HORIZONTAL_PULL|Тяга Т-грифа
legacy-19|Гиперэкстензия|BACK|STRENGTH|bodyweight|legacy|BACK_EXTENSION|Гиперэкстензия
legacy-20|Шраги со штангой|BACK|STRENGTH|barbell|legacy|SHRUG|Шраги со штангой
legacy-21|Приседания со штангой|LEGS|STRENGTH|barbell|legacy|SQUAT|Приседания со штангой
legacy-22|Приседания в Смите|LEGS|STRENGTH|barbell|legacy|SQUAT|Приседания в Смите
legacy-23|Гакк-приседания|LEGS|STRENGTH|barbell|legacy|SQUAT|Гакк-приседания
legacy-24|Жим ногами|LEGS|STRENGTH|bodyweight|legacy|LEG_PRESS|Жим ногами
legacy-25|Выпады с гантелями|LEGS|STRENGTH|dumbbell|legacy|SQUAT|Выпады с гантелями
legacy-26|Болгарские выпады|LEGS|STRENGTH|bodyweight|legacy|SQUAT|Болгарские выпады
legacy-27|Румынская тяга|LEGS|STRENGTH|bodyweight|legacy|HIP_HINGE|Румынская тяга
legacy-28|Разгибание ног в тренажёре|LEGS|STRENGTH|machine|legacy|LEG_EXTENSION|Разгибание ног в тренажёре
legacy-29|Сгибание ног в тренажёре|LEGS|STRENGTH|machine|legacy|LEG_CURL|Сгибание ног в тренажёре
legacy-30|Подъёмы на носки стоя|LEGS|STRENGTH|bodyweight|legacy|CALF_RAISE|Подъёмы на носки стоя
legacy-31|Подъёмы на носки сидя|LEGS|STRENGTH|bodyweight|legacy|CALF_RAISE|Подъёмы на носки сидя
legacy-32|Жим штанги стоя|SHOULDERS|STRENGTH|barbell|legacy|SHOULDER_PRESS|Жим штанги стоя
legacy-33|Жим гантелей сидя|SHOULDERS|STRENGTH|dumbbell|legacy|SHOULDER_PRESS|Жим гантелей сидя
legacy-34|Жим Арнольда|SHOULDERS|STRENGTH|bodyweight|legacy|SHOULDER_PRESS|Жим Арнольда
legacy-35|Махи гантелями в стороны|SHOULDERS|STRENGTH|dumbbell|legacy|LATERAL_RAISE|Махи гантелями в стороны
legacy-36|Махи гантелями в наклоне|SHOULDERS|STRENGTH|dumbbell|legacy|REAR_DELT_FLY|Махи гантелями в наклоне
legacy-37|Фронтальные махи гантелями|SHOULDERS|STRENGTH|dumbbell|legacy|FRONT_RAISE|Фронтальные махи гантелями
legacy-38|Тяга штанги к подбородку|SHOULDERS|STRENGTH|barbell|legacy|UPRIGHT_ROW|Тяга штанги к подбородку
legacy-39|Разведение в тренажёре на заднюю дельту|SHOULDERS|STRENGTH|machine|legacy|REAR_DELT_FLY|Разведение в тренажёре на заднюю дельту
legacy-40|Жим гантелей стоя|SHOULDERS|STRENGTH|dumbbell|legacy|SHOULDER_PRESS|Жим гантелей стоя
legacy-41|Подъём штанги на бицепс|ARMS|STRENGTH|barbell|legacy|CURL|Подъём штанги на бицепс
legacy-42|Подъём штанги на бицепс обратным хватом|ARMS|STRENGTH|barbell|legacy|CURL|Подъём штанги на бицепс обратным хватом
legacy-43|Подъём гантелей на бицепс сидя|ARMS|STRENGTH|dumbbell|legacy|CURL|Подъём гантелей на бицепс сидя
legacy-44|Молотки с гантелями|ARMS|STRENGTH|dumbbell|legacy|CURL|Молотки с гантелями
legacy-45|Концентрированный подъём на бицепс|ARMS|STRENGTH|bodyweight|legacy|CURL|Концентрированный подъём на бицепс
legacy-46|Французский жим лёжа|ARMS|STRENGTH|bodyweight|legacy|EXTENSION|Французский жим лёжа
legacy-47|Разгибание на блоке на трицепс|ARMS|STRENGTH|cable|legacy|EXTENSION|Разгибание на блоке на трицепс
legacy-48|Разгибание руки с гантелью из-за головы|ARMS|STRENGTH|dumbbell|legacy|EXTENSION|Разгибание руки с гантелью из-за головы
legacy-49|Отжимания на брусьях|ARMS|STRENGTH|bodyweight|legacy|FULL_BODY|Отжимания на брусьях
legacy-50|Скручивания|CORE|STRENGTH|bodyweight|legacy|CORE|Скручивания
legacy-51|Скручивания на блоке|CORE|STRENGTH|cable|legacy|CORE|Скручивания на блоке
legacy-52|Подъём ног в висе|CORE|STRENGTH|bodyweight|legacy|CORE|Подъём ног в висе
legacy-53|Подъём ног лёжа|CORE|STRENGTH|bodyweight|legacy|CORE|Подъём ног лёжа
legacy-54|Русские скручивания|CORE|STRENGTH|bodyweight|legacy|CORE|Русские скручивания
legacy-55|Велосипед|CORE|STRENGTH|bodyweight|legacy|CORE|Велосипед
legacy-56|Складка|CORE|STRENGTH|bodyweight|legacy|CORE|Складка
legacy-57|Планка|CORE|TIMED|bodyweight|legacy|CORE|Планка
legacy-58|Боковая планка|CORE|TIMED|bodyweight|legacy|CORE|Боковая планка
legacy-59|Беговая дорожка|CARDIO|CARDIO|cardio_machine|legacy|CARDIO|Беговая дорожка
legacy-60|Велотренажёр|CARDIO|CARDIO|cardio_machine|legacy|CARDIO|Велотренажёр
legacy-61|Эллиптический тренажёр|CARDIO|CARDIO|cardio_machine|legacy|CARDIO|Эллиптический тренажёр
legacy-62|Гребной тренажёр|CARDIO|CARDIO|cardio_machine|legacy|CARDIO|Гребной тренажёр
legacy-63|Степпер|CARDIO|CARDIO|cardio_machine|legacy|CARDIO|Степпер
legacy-64|Бёрпи|FULL_BODY|STRENGTH|bodyweight|legacy|FULL_BODY|Бёрпи
legacy-65|Трастеры со штангой|FULL_BODY|STRENGTH|barbell|legacy|FULL_BODY|Трастеры со штангой
legacy-66|Махи гирей|FULL_BODY|STRENGTH|kettlebell|legacy|FULL_BODY|Махи гирей
legacy-67|Турецкий подъём|FULL_BODY|STRENGTH|bodyweight|legacy|FULL_BODY|Турецкий подъём
legacy-68|Прыжки на тумбу|FULL_BODY|STRENGTH|bodyweight|legacy|SQUAT|Прыжки на тумбу
chest-1-1|Жим штанги узким хватом|CHEST|STRENGTH|barbell|chest|CHEST_PRESS|-
chest-1-2|Жим штанги на наклонной скамье 30 градусов|CHEST|STRENGTH|barbell|chest|CHEST_PRESS|-
chest-2-1|Жим гантелей на горизонтальной скамье|CHEST|STRENGTH|dumbbell|chest|CHEST_PRESS|-
chest-2-2|Жим гантелей на отрицательной скамье|CHEST|STRENGTH|dumbbell|chest|CHEST_PRESS|-
chest-3-1|Жим в Смите на наклонной|CHEST|STRENGTH|bodyweight|chest|CHEST_PRESS|-
chest-3-2|Жим в Смите лёжа|CHEST|STRENGTH|bodyweight|chest|CHEST_PRESS|-
chest-4-1|Отжимания на брусьях с наклоном|CHEST|STRENGTH|bodyweight|chest|CHEST_PRESS|-
chest-4-2|Отжимания с весом|CHEST|STRENGTH|bodyweight|chest|CHEST_PRESS|-
chest-5-1|Кроссовер сверху вниз|CHEST|STRENGTH|cable|chest|CHEST_FLY|-
chest-5-2|Кроссовер одной рукой|CHEST|STRENGTH|cable|chest|CHEST_FLY|-
chest-6-1|Кроссовер снизу вверх|CHEST|STRENGTH|cable|chest|CHEST_FLY|-
chest-6-2|Сведения рук с нижних блоков|CHEST|STRENGTH|cable|chest|CHEST_FLY|-
chest-7-1|Пек-дек|CHEST|STRENGTH|machine|chest|CHEST_FLY|-
chest-7-2|Жим в тренажёре converging|CHEST|STRENGTH|machine|chest|CHEST_PRESS|-
chest-8-1|Пуловер в блоке|CHEST|STRENGTH|cable|chest|CHEST_FLY|-
chest-8-2|Отжимания от скамьи|CHEST|STRENGTH|bodyweight|chest|CHEST_PRESS|-
chest-9-1|Жим в рычажном тренажёре|CHEST|STRENGTH|machine|chest|CHEST_PRESS|-
chest-9-2|Разводка в кроссовере лёжа|CHEST|STRENGTH|cable|chest|CHEST_FLY|-
chest-10-2|Жим гантелей нейтральным хватом|CHEST|STRENGTH|dumbbell|chest|CHEST_PRESS|-
chest-11-1|Жим на отрицательной скамье|CHEST|STRENGTH|bodyweight|chest|CHEST_PRESS|-
chest-11-2|Сведения рук в тренажёре|CHEST|STRENGTH|machine|chest|CHEST_FLY|-
chest-12-1|Сведения в тренажёре|CHEST|STRENGTH|machine|chest|CHEST_FLY|-
chest-12-2|Жим узким хватом в Смите|CHEST|STRENGTH|bodyweight|chest|CHEST_PRESS|-
shoulders-1-1|Армейский жим|SHOULDERS|STRENGTH|bodyweight|shoulders|SHOULDER_PRESS|-
shoulders-1-2|Жим в тренажёре на плечи|SHOULDERS|STRENGTH|machine|shoulders|SHOULDER_PRESS|-
shoulders-2-1|Жим в Смите сидя|SHOULDERS|STRENGTH|bodyweight|shoulders|SHOULDER_PRESS|-
shoulders-2-2|Подъём гантелей в стороны сидя|SHOULDERS|STRENGTH|dumbbell|shoulders|LATERAL_RAISE|-
shoulders-3-1|Подъём гантелей перед собой|SHOULDERS|STRENGTH|dumbbell|shoulders|FRONT_RAISE|-
shoulders-3-2|Подъём руки в сторону в кроссовере|SHOULDERS|STRENGTH|cable|shoulders|LATERAL_RAISE|-
shoulders-4-2|Разведения гантелей в наклоне на скамье|SHOULDERS|STRENGTH|dumbbell|shoulders|REAR_DELT_FLY|-
shoulders-5-1|Разведения в кроссовере|SHOULDERS|STRENGTH|cable|shoulders|REAR_DELT_FLY|-
shoulders-5-2|Face pull с канатом|SHOULDERS|STRENGTH|cable|shoulders|FACE_PULL|-
shoulders-6-1|Face pull|SHOULDERS|STRENGTH|cable|shoulders|FACE_PULL|-
shoulders-6-2|Наружная ротация блока|SHOULDERS|STRENGTH|cable|shoulders|ROTATOR_CUFF|-
shoulders-7-2|Внутренняя ротация блока|SHOULDERS|STRENGTH|cable|shoulders|ROTATOR_CUFF|-
shoulders-8-2|Скапулярные подтягивания|SHOULDERS|STRENGTH|bodyweight|shoulders|ROTATOR_CUFF|-
shoulders-9-1|Скапулярные отжимания|SHOULDERS|STRENGTH|bodyweight|shoulders|ROTATOR_CUFF|-
shoulders-9-2|Y-подъём на наклонной скамье|SHOULDERS|STRENGTH|bodyweight|shoulders|REAR_DELT_FLY|-
shoulders-10-1|Подъём штанги перед собой|SHOULDERS|STRENGTH|barbell|shoulders|FRONT_RAISE|-
shoulders-10-2|Шраги в тренажёре|SHOULDERS|STRENGTH|machine|shoulders|SHRUG|-
shoulders-11-1|Y-подъём|SHOULDERS|STRENGTH|bodyweight|shoulders|REAR_DELT_FLY|-
shoulders-11-2|Обратный пек-дек|SHOULDERS|STRENGTH|machine|shoulders|REAR_DELT_FLY|-
shoulders-12-1|Шраги с гантелями|SHOULDERS|STRENGTH|dumbbell|shoulders|SHRUG|-
shoulders-12-2|Тяга каната к лицу сидя|SHOULDERS|STRENGTH|cable|shoulders|FACE_PULL|-
back-1-1|Тяга блока прямыми руками|BACK|STRENGTH|cable|back|VERTICAL_PULL|-
back-1-2|Тяга к поясу в хаммере|BACK|STRENGTH|machine|back|HORIZONTAL_PULL|-
back-2-1|Тяга гантелей к поясу|BACK|STRENGTH|dumbbell|back|HORIZONTAL_PULL|-
back-2-2|Тяга гантелей с упором грудью|BACK|STRENGTH|dumbbell|back|HORIZONTAL_PULL|-
back-3-1|Тяга штанги Пендлея|BACK|STRENGTH|barbell|back|HORIZONTAL_PULL|-
back-3-2|Подтягивания широким хватом|BACK|STRENGTH|bodyweight|back|VERTICAL_PULL|-
back-4-1|Тяга в тренажёре с упором|BACK|STRENGTH|machine|back|HORIZONTAL_PULL|-
back-4-2|Тяга верхнего блока обратным хватом|BACK|STRENGTH|cable|back|VERTICAL_PULL|-
back-5-1|Подтягивания нейтральным хватом|BACK|STRENGTH|bodyweight|back|VERTICAL_PULL|-
back-5-2|Тяга нижнего блока широким хватом|BACK|STRENGTH|cable|back|HORIZONTAL_PULL|-
back-6-1|Тяга верхнего блока узким хватом|BACK|STRENGTH|cable|back|VERTICAL_PULL|-
back-6-2|Тяга одной рукой в кроссовере|BACK|STRENGTH|cable|back|HORIZONTAL_PULL|-
back-7-1|Тяга нижнего блока узким хватом|BACK|STRENGTH|cable|back|HORIZONTAL_PULL|-
back-7-2|Обратная гиперэкстензия|BACK|STRENGTH|bodyweight|back|BACK_EXTENSION|-
back-8-2|Разгибание спины в тренажёре|BACK|STRENGTH|machine|back|BACK_EXTENSION|-
back-9-2|Шраги в Смите|BACK|STRENGTH|bodyweight|back|SHRUG|-
back-10-2|Фермерская прогулка|BACK|STRENGTH|bodyweight|back|SHRUG|-
back-11-2|Тяга к поясу с V-рукоятью|BACK|STRENGTH|bodyweight|back|HORIZONTAL_PULL|-
back-12-2|Подтягивания в гравитроне|BACK|STRENGTH|machine|back|VERTICAL_PULL|-
arms-1-1|Сгибание EZ-грифа|ARMS|STRENGTH|barbell|arms|CURL|-
arms-1-2|Сгибание штанги стоя|ARMS|STRENGTH|barbell|arms|CURL|-
arms-2-1|Сгибание на блоке|ARMS|STRENGTH|cable|arms|CURL|-
arms-2-2|Сгибание на нижнем блоке одной рукой|ARMS|STRENGTH|cable|arms|CURL|-
arms-3-1|Сгибание на скамье Скотта|ARMS|STRENGTH|bodyweight|arms|CURL|-
arms-3-2|Сгибание гантелей на скамье Скотта|ARMS|STRENGTH|dumbbell|arms|CURL|-
arms-4-1|Сгибание обратным хватом|ARMS|STRENGTH|bodyweight|arms|CURL|-
arms-4-2|Сгибание обратным хватом на блоке|ARMS|STRENGTH|cable|arms|CURL|-
arms-5-1|Разгибание канатом|ARMS|STRENGTH|cable|arms|EXTENSION|-
arms-6-1|Разгибание EZ-грифа над головой|ARMS|STRENGTH|barbell|arms|EXTENSION|-
arms-6-2|Разгибание гантели над головой двумя руками|ARMS|STRENGTH|dumbbell|arms|EXTENSION|-
arms-7-1|Узкие отжимания|ARMS|STRENGTH|bodyweight|arms|EXTENSION|-
arms-7-2|Узкие отжимания от скамьи|ARMS|STRENGTH|bodyweight|arms|EXTENSION|-
arms-8-1|Разгибание одной рукой на блоке|ARMS|STRENGTH|cable|arms|EXTENSION|-
arms-9-1|Сгибание кистей|ARMS|STRENGTH|bodyweight|arms|CURL|-
arms-9-2|Сгибание кистей с гантелями|ARMS|STRENGTH|dumbbell|arms|CURL|-
arms-10-1|Разгибание кистей|ARMS|STRENGTH|bodyweight|arms|EXTENSION|-
arms-10-2|Разгибание кистей с гантелями|ARMS|STRENGTH|dumbbell|arms|EXTENSION|-
arms-11-1|Удержание блинов|ARMS|STRENGTH|bodyweight|arms|FOREARM|-
arms-12-1|Сгибание молотком на блоке|ARMS|STRENGTH|cable|arms|CURL|-
arms-12-2|Сгибание молотком с канатом|ARMS|STRENGTH|cable|arms|CURL|-
legs-1-1|Фронтальный присед|LEGS|STRENGTH|barbell|legs|SQUAT|-
legs-1-2|Фронтальный присед в Смите|LEGS|STRENGTH|barbell|legs|SQUAT|-
legs-2-1|Присед с паузой|LEGS|STRENGTH|barbell|legs|SQUAT|-
legs-2-2|Гоблет-присед|LEGS|STRENGTH|barbell|legs|SQUAT|-
legs-3-1|Темповый присед|LEGS|STRENGTH|barbell|legs|SQUAT|-
legs-3-2|Тяга сумо с гирей|LEGS|STRENGTH|kettlebell|legs|HIP_HINGE|-
legs-4-1|Тяга сумо|LEGS|STRENGTH|bodyweight|legs|HIP_HINGE|-
legs-4-2|Тяга trap-bar с высокими ручками|LEGS|STRENGTH|bodyweight|legs|HIP_HINGE|-
legs-5-1|Тяга trap-bar|LEGS|STRENGTH|bodyweight|legs|HIP_HINGE|-
legs-5-2|Ягодичный мост с гантелью|LEGS|STRENGTH|dumbbell|legs|HIP_HINGE|-
legs-6-1|Ягодичный мост|LEGS|STRENGTH|bodyweight|legs|HIP_HINGE|-
legs-6-2|Хип-траст в тренажёре|LEGS|STRENGTH|machine|legs|HIP_HINGE|-
legs-7-1|Хип-траст в Смите|LEGS|STRENGTH|bodyweight|legs|HIP_HINGE|-
legs-7-2|Отведение бедра в кроссовере|LEGS|STRENGTH|cable|legs|HIP_ABDUCTION|-
legs-8-1|Отведение бедра в тренажёре|LEGS|STRENGTH|machine|legs|HIP_ABDUCTION|-
legs-8-2|Приведение бедра в кроссовере|LEGS|STRENGTH|cable|legs|HIP_ADDUCTION|-
legs-9-1|Приведение бедра в тренажёре|LEGS|STRENGTH|machine|legs|HIP_ADDUCTION|-
legs-9-2|Подъём на носки в тренажёре стоя|LEGS|STRENGTH|machine|legs|CALF_RAISE|-
legs-10-1|Подъём на носки в жиме ногами|LEGS|STRENGTH|bodyweight|legs|CALF_RAISE|-
legs-10-2|Подъём носков у стены|LEGS|STRENGTH|bodyweight|legs|TIBIALIS_RAISE|-
legs-11-1|Подъём носков|LEGS|STRENGTH|bodyweight|legs|TIBIALIS_RAISE|-
legs-11-2|Шаги на тумбу с гирями|LEGS|STRENGTH|kettlebell|legs|SQUAT|-
legs-12-1|Шаги на тумбу|LEGS|STRENGTH|bodyweight|legs|SQUAT|-
legs-12-2|Выпады назад с гантелями|LEGS|STRENGTH|dumbbell|legs|SQUAT|-
core-1-1|Dead bug|CORE|STRENGTH|bodyweight|core|CORE|-
core-1-2|Dead bug с резиной|CORE|STRENGTH|bodyweight|core|CORE|-
core-2-1|Pallof press|CORE|STRENGTH|cable|core|CORE|-
core-3-1|Колесо для пресса|CORE|STRENGTH|bodyweight|core|CORE|-
core-3-2|Колесо для пресса с колен|CORE|STRENGTH|bodyweight|core|CORE|-
core-4-1|Перенос чемодана|CORE|STRENGTH|bodyweight|core|CORE|-
core-4-2|Перенос чемодана с гирей|CORE|STRENGTH|kettlebell|core|CORE|-
core-5-1|Планка с весом|CORE|STRENGTH|bodyweight|core|CORE|-
core-6-1|Боковая планка с весом|CORE|STRENGTH|bodyweight|core|CORE|-
core-7-1|Дровосек в блоке|CORE|STRENGTH|cable|core|CORE|-
core-8-1|Обратные скручивания|CORE|STRENGTH|bodyweight|core|CORE|-
core-9-1|Подъём коленей|CORE|STRENGTH|bodyweight|core|CORE|-
core-9-2|Подъём коленей в тренажёре|CORE|STRENGTH|machine|core|CORE|-
core-10-1|Вакуум|CORE|STRENGTH|bodyweight|core|CORE|-
core-10-2|Вакуум лёжа|CORE|STRENGTH|bodyweight|core|CORE|-
core-11-1|Антиротация с резинкой|CORE|STRENGTH|bodyweight|core|CORE_ANTI_ROTATION|-
core-12-1|Перенос над головой|CORE|STRENGTH|bodyweight|core|CORE|-
core-12-2|Перенос гири над головой|CORE|STRENGTH|kettlebell|core|CORE|-
powerlifting-1-1|Соревновательный присед|FULL_BODY|STRENGTH|barbell|powerlifting|SQUAT|-
powerlifting-1-2|Соревновательный присед в экипировке|FULL_BODY|STRENGTH|barbell|powerlifting|SQUAT|-
powerlifting-2-1|Соревновательный жим|FULL_BODY|STRENGTH|bodyweight|powerlifting|CHEST_PRESS|-
powerlifting-2-2|Соревновательный жим с паузой|FULL_BODY|STRENGTH|bodyweight|powerlifting|CHEST_PRESS|-
powerlifting-3-1|Соревновательная становая|FULL_BODY|STRENGTH|barbell|powerlifting|HIP_HINGE|-
powerlifting-3-2|Соревновательная становая сумо|FULL_BODY|STRENGTH|barbell|powerlifting|HIP_HINGE|-
powerlifting-4-1|Жим с паузой|FULL_BODY|STRENGTH|bodyweight|powerlifting|CHEST_PRESS|-
powerlifting-4-2|Жим с паузой узким хватом|FULL_BODY|STRENGTH|bodyweight|powerlifting|CHEST_PRESS|-
powerlifting-5-1|Становая с паузой|FULL_BODY|STRENGTH|barbell|powerlifting|HIP_HINGE|-
powerlifting-5-2|Становая с паузой ниже колена|FULL_BODY|STRENGTH|barbell|powerlifting|HIP_HINGE|-
powerlifting-6-1|Присед на ящик|FULL_BODY|STRENGTH|barbell|powerlifting|SQUAT|-
powerlifting-7-1|Жим с доски|FULL_BODY|STRENGTH|bodyweight|powerlifting|CHEST_PRESS|-
powerlifting-8-1|Дефицитная становая|FULL_BODY|STRENGTH|barbell|powerlifting|HIP_HINGE|-
powerlifting-9-1|Тяга с блоков|FULL_BODY|STRENGTH|barbell|powerlifting|HIP_HINGE|-
powerlifting-9-2|Тяга с блоков сумо|FULL_BODY|STRENGTH|barbell|powerlifting|HIP_HINGE|-
powerlifting-10-1|Темповый жим|FULL_BODY|STRENGTH|bodyweight|powerlifting|CHEST_PRESS|-
powerlifting-10-2|Темповый жим с гантелями|FULL_BODY|STRENGTH|dumbbell|powerlifting|CHEST_PRESS|-
powerlifting-11-1|Присед с темпом|FULL_BODY|STRENGTH|barbell|powerlifting|SQUAT|-
powerlifting-11-2|Присед с темпом в Смите|FULL_BODY|STRENGTH|barbell|powerlifting|SQUAT|-
powerlifting-12-1|Жим ногами пауза|FULL_BODY|STRENGTH|bodyweight|powerlifting|LEG_PRESS|-
powerlifting-12-2|Жим ногами с паузой|FULL_BODY|STRENGTH|bodyweight|powerlifting|LEG_PRESS|-
calisthenics-1-1|Подтягивания на кольцах|FULL_BODY|STRENGTH|rings|calisthenics|VERTICAL_PULL|-
calisthenics-1-2|Тяга на кольцах|FULL_BODY|STRENGTH|rings|calisthenics|HORIZONTAL_PULL|-
calisthenics-2-1|Отжимания на кольцах|FULL_BODY|STRENGTH|rings|calisthenics|CHEST_PRESS|-
calisthenics-2-2|Упор на кольцах|FULL_BODY|STRENGTH|rings|calisthenics|SHOULDER_PRESS|-
calisthenics-3-1|Стойка на руках|FULL_BODY|STRENGTH|bodyweight|calisthenics|SHOULDER_PRESS|-
calisthenics-3-2|Стойка на руках у стены|FULL_BODY|STRENGTH|bodyweight|calisthenics|FULL_BODY|-
calisthenics-4-1|Отжимания в стойке|FULL_BODY|STRENGTH|bodyweight|calisthenics|SHOULDER_PRESS|-
calisthenics-4-2|Отжимания в стойке у стены|FULL_BODY|STRENGTH|bodyweight|calisthenics|FULL_BODY|-
calisthenics-5-1|Пистолетик|FULL_BODY|STRENGTH|bodyweight|calisthenics|SQUAT|-
calisthenics-5-2|Пистолетик с опорой|FULL_BODY|STRENGTH|bodyweight|calisthenics|SQUAT|-
calisthenics-6-1|Скандинавские сгибания|FULL_BODY|STRENGTH|bodyweight|calisthenics|LEG_CURL|-
calisthenics-6-2|Скандинавские сгибания с резиной|FULL_BODY|STRENGTH|bodyweight|calisthenics|LEG_CURL|-
calisthenics-7-1|Австралийские подтягивания|FULL_BODY|STRENGTH|bodyweight|calisthenics|HORIZONTAL_PULL|-
calisthenics-7-2|Австралийские подтягивания на кольцах|FULL_BODY|STRENGTH|rings|calisthenics|HORIZONTAL_PULL|-
calisthenics-8-1|L-sit|FULL_BODY|STRENGTH|bodyweight|calisthenics|CORE_ANTI_ROTATION|-
calisthenics-8-2|L-sit на паралетсах|FULL_BODY|STRENGTH|bodyweight|calisthenics|CORE_ANTI_ROTATION|-
calisthenics-9-1|Подъём переворотом|FULL_BODY|STRENGTH|bodyweight|calisthenics|FULL_BODY|-
calisthenics-9-2|Подъём переворотом с резиной|FULL_BODY|STRENGTH|bodyweight|calisthenics|FULL_BODY|-
calisthenics-10-1|Muscle-up|FULL_BODY|STRENGTH|bodyweight|calisthenics|FULL_BODY|-
calisthenics-10-2|Muscle-up на перекладине|FULL_BODY|STRENGTH|bodyweight|calisthenics|FULL_BODY|-
calisthenics-11-1|Отжимания с возвышения|FULL_BODY|STRENGTH|bodyweight|calisthenics|FULL_BODY|-
calisthenics-11-2|Отжимания с ногами на возвышении|FULL_BODY|STRENGTH|bodyweight|calisthenics|FULL_BODY|-
calisthenics-12-1|Прыжковые приседания|FULL_BODY|STRENGTH|barbell|calisthenics|SQUAT|-
calisthenics-12-2|Прыжковые выпады|FULL_BODY|STRENGTH|bodyweight|calisthenics|SQUAT|-
cardio-1-1|Ходьба на дорожке|CARDIO|CARDIO|cardio_machine|cardio|CARDIO|-
cardio-1-2|Ходьба на дорожке с наклоном|CARDIO|CARDIO|cardio_machine|cardio|CARDIO|-
cardio-2-1|Бег на дорожке|CARDIO|CARDIO|cardio_machine|cardio|CARDIO|-
cardio-2-2|Интервальный бег на дорожке|CARDIO|CARDIO|cardio_machine|cardio|CARDIO|-
cardio-3-1|Интервалы на велотренажёре|CARDIO|CARDIO|cardio_machine|cardio|CARDIO|-
cardio-3-2|Велотренажёр в ровном темпе|CARDIO|CARDIO|cardio_machine|cardio|CARDIO|-
cardio-4-1|Гребля|CARDIO|CARDIO|cardio_machine|cardio|CARDIO|-
cardio-4-2|Интервальная гребля|CARDIO|CARDIO|cardio_machine|cardio|CARDIO|-
cardio-5-1|Лестничный тренажёр|CARDIO|CARDIO|cardio_machine|cardio|CARDIO|-
cardio-5-2|Степпер в интервальном темпе|CARDIO|CARDIO|cardio_machine|cardio|CARDIO|-
cardio-6-1|Скакалка|CARDIO|CARDIO|bodyweight|cardio|CARDIO|-
cardio-6-2|Скакалка двойные прыжки|CARDIO|CARDIO|bodyweight|cardio|CARDIO|-
cardio-7-1|Air bike|CARDIO|CARDIO|cardio_machine|cardio|CARDIO|-
cardio-7-2|Air bike интервалы|CARDIO|CARDIO|cardio_machine|cardio|CARDIO|-
cardio-8-1|SkiErg|CARDIO|CARDIO|cardio_machine|cardio|CARDIO|-
cardio-8-2|SkiErg интервалы|CARDIO|CARDIO|cardio_machine|cardio|CARDIO|-
cardio-9-1|Орбитрек|CARDIO|CARDIO|cardio_machine|cardio|CARDIO|-
cardio-9-2|Орбитрек с сопротивлением|CARDIO|CARDIO|cardio_machine|cardio|CARDIO|-
cardio-10-1|Горная ходьба|CARDIO|CARDIO|bodyweight|cardio|CARDIO|-
cardio-10-2|Горная ходьба на дорожке|CARDIO|CARDIO|cardio_machine|cardio|CARDIO|-
cardio-11-1|Лёгкий бег|CARDIO|CARDIO|bodyweight|cardio|CARDIO|-
cardio-11-2|Темповый бег|CARDIO|CARDIO|bodyweight|cardio|CARDIO|-
cardio-12-1|Плавный степпер|CARDIO|CARDIO|cardio_machine|cardio|CARDIO|-
cardio-12-2|Плавание кролем|CARDIO|CARDIO|bodyweight|cardio|CARDIO|-
    """
  val entries =
      registryRows.trimIndent().lineSequence().filter(String::isNotBlank).map(::parse).toList()

  init {
    require(entries.size in 250..350)
    require(entries.map(Entry::key).distinct().size == entries.size)
    require(entries.map { it.exercise.syncId }.distinct().size == entries.size)
  }

  fun match(e: ExerciseEntity): Entry? =
      entries.firstOrNull { it.exercise.syncId == e.syncId }
          ?: if (!e.isCustom)
              entries.firstOrNull { e.syncId in it.legacySyncIds || e.name in it.legacyNames }
          else null

  fun isBuiltIn(e: ExerciseEntity) = match(e) != null

  fun loadsFor(e: ExerciseEntity) = match(e)?.loads
}
