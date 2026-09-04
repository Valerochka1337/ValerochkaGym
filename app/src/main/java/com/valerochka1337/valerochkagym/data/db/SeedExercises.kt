package com.valerochka1337.valerochkagym.data.db

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.data.db.entity.builtInExerciseSyncId

private fun exercise(
    name: String,
    group: MuscleGroup,
    type: ExerciseType = ExerciseType.STRENGTH,
): ExerciseEntity =
    ExerciseEntity(
        name = name,
        muscleGroup = group,
        type = type,
        isCustom = false,
        syncId = builtInExerciseSyncId(name),
        // Seed — базовая версия, чтобы recovery с любого реального backup всегда мог её заменить.
        updatedAt = 1,
    )

/** Built-in exercise catalogue seeded on first launch. */
internal val legacySeedExercises: List<ExerciseEntity> =
    listOf(
        // CHEST
        exercise("Жим штанги лёжа", MuscleGroup.CHEST),
        exercise("Жим гантелей лёжа", MuscleGroup.CHEST),
        exercise("Жим штанги на наклонной скамье", MuscleGroup.CHEST),
        exercise("Жим гантелей на наклонной скамье", MuscleGroup.CHEST),
        exercise("Разводка гантелей лёжа", MuscleGroup.CHEST),
        exercise("Сведение гантелей на наклонной скамье", MuscleGroup.CHEST),
        exercise("Отжимания от пола", MuscleGroup.CHEST),
        exercise("Сведение рук в кроссовере", MuscleGroup.CHEST),
        exercise("Жим в тренажёре Хаммер", MuscleGroup.CHEST),
        exercise("Пуловер с гантелью", MuscleGroup.CHEST),

        // BACK
        exercise("Подтягивания", MuscleGroup.BACK),
        exercise("Подтягивания обратным хватом", MuscleGroup.BACK),
        exercise("Тяга штанги в наклоне", MuscleGroup.BACK),
        exercise("Тяга верхнего блока", MuscleGroup.BACK),
        exercise("Тяга нижнего блока", MuscleGroup.BACK),
        exercise("Тяга гантели одной рукой", MuscleGroup.BACK),
        exercise("Становая тяга", MuscleGroup.BACK),
        exercise("Тяга Т-грифа", MuscleGroup.BACK),
        exercise("Гиперэкстензия", MuscleGroup.BACK),
        exercise("Шраги со штангой", MuscleGroup.BACK),

        // LEGS
        exercise("Приседания со штангой", MuscleGroup.LEGS),
        exercise("Приседания в Смите", MuscleGroup.LEGS),
        exercise("Гакк-приседания", MuscleGroup.LEGS),
        exercise("Жим ногами", MuscleGroup.LEGS),
        exercise("Выпады с гантелями", MuscleGroup.LEGS),
        exercise("Болгарские выпады", MuscleGroup.LEGS),
        exercise("Румынская тяга", MuscleGroup.LEGS),
        exercise("Разгибание ног в тренажёре", MuscleGroup.LEGS),
        exercise("Сгибание ног в тренажёре", MuscleGroup.LEGS),
        exercise("Подъёмы на носки стоя", MuscleGroup.LEGS),
        exercise("Подъёмы на носки сидя", MuscleGroup.LEGS),

        // SHOULDERS
        exercise("Жим штанги стоя", MuscleGroup.SHOULDERS),
        exercise("Жим гантелей сидя", MuscleGroup.SHOULDERS),
        exercise("Жим Арнольда", MuscleGroup.SHOULDERS),
        exercise("Махи гантелями в стороны", MuscleGroup.SHOULDERS),
        exercise("Махи гантелями в наклоне", MuscleGroup.SHOULDERS),
        exercise("Фронтальные махи гантелями", MuscleGroup.SHOULDERS),
        exercise("Тяга штанги к подбородку", MuscleGroup.SHOULDERS),
        exercise("Разведение в тренажёре на заднюю дельту", MuscleGroup.SHOULDERS),
        exercise("Жим гантелей стоя", MuscleGroup.SHOULDERS),

        // ARMS
        exercise("Подъём штанги на бицепс", MuscleGroup.ARMS),
        exercise("Подъём штанги на бицепс обратным хватом", MuscleGroup.ARMS),
        exercise("Подъём гантелей на бицепс сидя", MuscleGroup.ARMS),
        exercise("Молотки с гантелями", MuscleGroup.ARMS),
        exercise("Концентрированный подъём на бицепс", MuscleGroup.ARMS),
        exercise("Французский жим лёжа", MuscleGroup.ARMS),
        exercise("Разгибание на блоке на трицепс", MuscleGroup.ARMS),
        exercise("Разгибание руки с гантелью из-за головы", MuscleGroup.ARMS),
        exercise("Отжимания на брусьях", MuscleGroup.ARMS),

        // CORE
        exercise("Скручивания", MuscleGroup.CORE),
        exercise("Скручивания на блоке", MuscleGroup.CORE),
        exercise("Подъём ног в висе", MuscleGroup.CORE),
        exercise("Подъём ног лёжа", MuscleGroup.CORE),
        exercise("Русские скручивания", MuscleGroup.CORE),
        exercise("Велосипед", MuscleGroup.CORE),
        exercise("Складка", MuscleGroup.CORE),
        exercise("Планка", MuscleGroup.CORE, ExerciseType.TIMED),
        exercise("Боковая планка", MuscleGroup.CORE, ExerciseType.TIMED),

        // CARDIO
        exercise("Беговая дорожка", MuscleGroup.CARDIO, ExerciseType.CARDIO),
        exercise("Велотренажёр", MuscleGroup.CARDIO, ExerciseType.CARDIO),
        exercise("Эллиптический тренажёр", MuscleGroup.CARDIO, ExerciseType.CARDIO),
        exercise("Гребной тренажёр", MuscleGroup.CARDIO, ExerciseType.CARDIO),
        exercise("Степпер", MuscleGroup.CARDIO, ExerciseType.CARDIO),

        // FULL_BODY
        exercise("Бёрпи", MuscleGroup.FULL_BODY),
        exercise("Трастеры со штангой", MuscleGroup.FULL_BODY),
        exercise("Махи гирей", MuscleGroup.FULL_BODY),
        exercise("Турецкий подъём", MuscleGroup.FULL_BODY),
        exercise("Прыжки на тумбу", MuscleGroup.FULL_BODY),
    )

/** Fresh installations always receive the reviewed canonical registry, never the legacy subset. */
val seedExercises: List<ExerciseEntity>
  get() = CanonicalExerciseRegistry.entries.map { it.exercise }
